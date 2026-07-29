package hu.dojcsak.openrewrite.recipe.spring;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts Spring {@code @Autowired} field injection to constructor injection via Lombok
 * {@code @RequiredArgsConstructor}: matched fields become {@code final} (with {@code @Autowired}
 * removed), and the class gets a single, implicitly-selectable constructor.
 *
 * <p>A candidate is any class with a Spring stereotype annotation (
 * {@code @Service}/{@code @Component}/{@code @Repository}/{@code @Controller}/{@code @RestController})
 * and at least one {@code @Autowired} field. A candidate is <b>excluded</b> from automatic
 * conversion (left unchanged, flagged with a {@code // TODO} comment, and recorded in the
 * {@link FieldInjectionConversionReport} data table with a reason code) when any of:
 * <ul>
 *   <li><b>A</b> — an ancestor class (anywhere in the superclass chain, including unresolved/
 *       external ancestors) has its own {@code @Autowired} field or an uninitialized {@code final}
 *       field: Lombok's generated constructor never calls {@code super(...)} with arguments, so
 *       the ancestor's own required fields would never be set.</li>
 *   <li><b>B</b> — the class is a JAX-WS/CXF endpoint ({@code @WebService}, or implements a
 *       {@code *WSInterface}-style type) AND a reflective, {@code Class}-based registration call
 *       site was found for it (e.g. {@code JaxWsServerFactoryBean#setServiceClass(X.class)}, or
 *       {@code Endpoint.publish(address, new X())}). If no registration evidence is found either
 *       way, the class is still converted (see class-level note on this trade-off).</li>
 *   <li><b>C</b> — a direct {@code new X(...)} call site was found anywhere in the scanned source
 *       set: changing the constructor signature would break that call.</li>
 *   <li><b>D</b> — the class already has a constructor that isn't empty/a bare {@code super()}
 *       call (including an already-present {@code @RequiredArgsConstructor}/
 *       {@code @AllArgsConstructor}, which may not match the post-conversion final-field set).</li>
 * </ul>
 * An existing {@code @NoArgsConstructor} is not an exclusion reason by itself — it is replaced
 * with {@code @RequiredArgsConstructor}, since a class can still be constructor-injected by Spring
 * (or passed as an already-constructed instance, e.g. into a {@code @Bean} factory method
 * parameter) regardless of whether it also happens to carry an unrelated no-arg constructor.
 *
 * <p>Because these exclusion checks require whole-codebase data (ancestor fields possibly
 * declared in another file, registration/instantiation call sites possibly in another file),
 * this recipe is a {@link ScanningRecipe}: the scan phase visits every source file to build a
 * complete accumulator before the edit phase decides anything. If the recipe runs on a partial
 * source set (e.g. a single module out of a multi-module reactor), missing data degrades toward
 * <em>more</em> exclusions (reason A) or, for reason B, toward converting when no registration
 * evidence is visible at all — see the class Javadoc above.
 *
 * <p><b>Known limitation</b>: if an {@code @Autowired} field also carries another annotation that
 * needs to move to the generated constructor parameter to keep working (most notably
 * {@code @Qualifier}), this recipe does not move it — per its contract it leaves every annotation
 * other than {@code @Autowired} untouched. Such a field is still finalized and included in the
 * constructor, but the extra annotation stays on the field declaration where Spring's constructor
 * injection no longer reads it.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ConvertFieldInjectionToLombokConstructorInjection extends ScanningRecipe<ConvertFieldInjectionToLombokConstructorInjection.Accumulator> {

    private static final AnnotationMatcher AUTOWIRED =
            new AnnotationMatcher("@org.springframework.beans.factory.annotation.Autowired");
    private static final List<AnnotationMatcher> STEREOTYPES = Arrays.asList(
            new AnnotationMatcher("@org.springframework.stereotype.Service"),
            new AnnotationMatcher("@org.springframework.stereotype.Component"),
            new AnnotationMatcher("@org.springframework.stereotype.Repository"),
            new AnnotationMatcher("@org.springframework.stereotype.Controller"),
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RestController"));
    private static final List<AnnotationMatcher> WEB_SERVICE = Arrays.asList(
            new AnnotationMatcher("@jakarta.jws.WebService"),
            new AnnotationMatcher("@javax.jws.WebService"));
    private static final AnnotationMatcher REQUIRED_ARGS_CONSTRUCTOR =
            new AnnotationMatcher("@lombok.RequiredArgsConstructor");
    private static final AnnotationMatcher ALL_ARGS_CONSTRUCTOR =
            new AnnotationMatcher("@lombok.AllArgsConstructor");
    private static final AnnotationMatcher NO_ARGS_CONSTRUCTOR =
            new AnnotationMatcher("@lombok.NoArgsConstructor");

    // Reflective (Class-based) registration APIs. `setServiceClass` itself is matched by name +
    // receiver static type (see isCxfReceiver below), not MethodMatcher, since it is typically
    // inherited from an abstract CXF base class that varies across CXF versions.
    private static final MethodMatcher ENDPOINT_PUBLISH_JAKARTA =
            new MethodMatcher("jakarta.xml.ws.Endpoint publish(java.lang.String, java.lang.Object)");
    private static final MethodMatcher ENDPOINT_PUBLISH_JAVAX =
            new MethodMatcher("javax.xml.ws.Endpoint publish(java.lang.String, java.lang.Object)");
    // Instance-based registration idiom actually used in practice (Spring @Bean method receives an
    // already-Spring-managed impl parameter, wraps it): new EndpointImpl(bus, impl).
    private static final MethodMatcher NEW_ENDPOINT_IMPL =
            new MethodMatcher("org.apache.cxf.jaxws.EndpointImpl <constructor>(..)");

    String displayName = "Convert @Autowired field injection to Lombok constructor injection";

    String description = "Converts Spring @Autowired field injection to constructor injection via " +
            "Lombok @RequiredArgsConstructor. Only converts classes with a Spring stereotype " +
            "annotation and at least one @Autowired field, and only when doing so is provably safe: " +
            "no ancestor class has its own injected/final fields (Lombok does not call super(...) " +
            "with arguments), no reflective/Class-based JAX-WS or CXF registration was found for " +
            "WebService endpoints, no direct `new X(...)` call site exists anywhere in the scanned " +
            "codebase, and no incompatible existing constructor is present. Excluded classes are " +
            "left unchanged, flagged with a TODO comment, and recorded (with the exclusion reason) " +
            "in the FieldInjectionConversionReport data table for manual follow-up.";

    transient FieldInjectionConversionReport report = new FieldInjectionConversionReport(this);

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    // -------------------------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------------------------

    public static class Accumulator {
        // Every class in the scanned source set (not just candidates) — needed to look up
        // ancestor field risk when evaluating a candidate's own superclass chain.
        Map<String, ClassInfo> classesByFqn = new HashMap<>();
        // FQN of a class -> evidence of how it is registered/instantiated as a JAX-WS endpoint.
        Map<String, RegistrationEvidence> registrationByFqn = new HashMap<>();
        // FQN of a class -> every `new X(...)` call site found anywhere in the source set.
        Map<String, List<NewClassCallSite>> directInstantiationsByFqn = new HashMap<>();
    }

    enum ConstructorShape {NONE, TRIVIAL_NO_ARG, OTHER}

    static class ClassInfo {
        String fqn;
        String sourcePath;
        boolean hasSpringStereotype;
        boolean isWebServiceEndpoint;
        List<String> autowiredFieldNames = new ArrayList<>();
        boolean hasOwnAutowiredOrUninitializedFinalField;
        String supertypeFqn;
        boolean hasUnresolvedExtendsClause;
        String extendsClauseText;
        ConstructorShape constructorShape = ConstructorShape.NONE;
        boolean hasNoArgsConstructor;

        boolean isCandidate() {
            return hasSpringStereotype && !autowiredFieldNames.isEmpty();
        }
    }

    static class RegistrationEvidence {
        boolean classBasedFound;
        boolean instanceBasedFound;
    }

    static class NewClassCallSite {
        final String location;

        NewClassCallSite(String location) {
            this.location = location;
        }
    }

    enum ExclusionReason {A, B, C, D}

    static class ExclusionResult {
        final ExclusionReason reason;
        final String detail;

        ExclusionResult(ExclusionReason reason, String detail) {
            this.reason = reason;
            this.detail = detail;
        }
    }

    // -------------------------------------------------------------------------------------------
    // Phase 1 + 2 data collection (scan)
    // -------------------------------------------------------------------------------------------

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (cd.getKind() != J.ClassDeclaration.Kind.Type.Class) {
                    return cd;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(cd.getType());
                if (type == null) {
                    return cd;
                }

                ClassInfo info = new ClassInfo();
                info.fqn = type.getFullyQualifiedName();
                info.sourcePath = sourcePath();
                info.hasSpringStereotype = cd.getLeadingAnnotations().stream()
                        .anyMatch(a -> STEREOTYPES.stream().anyMatch(m -> m.matches(a)));
                info.isWebServiceEndpoint = isWebServiceEndpoint(cd, type);
                info.hasNoArgsConstructor = cd.getLeadingAnnotations().stream().anyMatch(NO_ARGS_CONSTRUCTOR::matches);
                boolean hasRequiredArgsConstructor = cd.getLeadingAnnotations().stream()
                        .anyMatch(REQUIRED_ARGS_CONSTRUCTOR::matches);
                boolean hasAllArgsConstructor = cd.getLeadingAnnotations().stream()
                        .anyMatch(ALL_ARGS_CONSTRUCTOR::matches);

                if (cd.getExtends() != null) {
                    JavaType.FullyQualified supertype = TypeUtils.asFullyQualified(cd.getExtends().getType());
                    if (supertype != null) {
                        info.supertypeFqn = supertype.getFullyQualifiedName();
                    } else {
                        // extends clause present in source, but the type couldn't be resolved
                        // (external/unresolvable ancestor) -- evaluate() treats this as unsafe.
                        info.hasUnresolvedExtendsClause = true;
                        info.extendsClauseText = cd.getExtends().printTrimmed(getCursor());
                    }
                }

                List<J.MethodDeclaration> constructors = new ArrayList<>();
                for (Statement stmt : cd.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        if (vd.hasModifier(J.Modifier.Type.Static)) {
                            continue;
                        }
                        boolean autowired = vd.getLeadingAnnotations().stream().anyMatch(AUTOWIRED::matches);
                        boolean finalNoInit = vd.hasModifier(J.Modifier.Type.Final) &&
                                vd.getVariables().stream().anyMatch(nv -> nv.getInitializer() == null);
                        if (autowired || finalNoInit) {
                            info.hasOwnAutowiredOrUninitializedFinalField = true;
                        }
                        if (autowired) {
                            for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                                info.autowiredFieldNames.add(nv.getSimpleName());
                            }
                        }
                    } else if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration m = (J.MethodDeclaration) stmt;
                        if (m.getReturnTypeExpression() == null) {
                            constructors.add(m);
                        }
                    }
                }
                info.constructorShape = (hasRequiredArgsConstructor || hasAllArgsConstructor)
                        // An existing Lombok constructor-generating annotation may not match the
                        // post-conversion final-field set — fold into "OTHER" so it is caught by
                        // exclusion reason D rather than silently duplicated/conflicting.
                        ? ConstructorShape.OTHER
                        : classifyConstructors(constructors);

                acc.classesByFqn.put(info.fqn, info);
                return cd;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if ("setServiceClass".equals(m.getSimpleName()) && m.getArguments().size() == 1 && isCxfReceiver(m)) {
                    recordClassBased(m.getArguments().get(0));
                } else if ((ENDPOINT_PUBLISH_JAKARTA.matches(m) || ENDPOINT_PUBLISH_JAVAX.matches(m))
                        && m.getArguments().size() > 1) {
                    Expression implArg = m.getArguments().get(1);
                    if (implArg instanceof J.NewClass) {
                        recordClassBasedByType(implArg.getType());
                    } else {
                        recordInstanceBased(implArg);
                    }
                }
                return m;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass nc = super.visitNewClass(newClass, ctx);
                if (NEW_ENDPOINT_IMPL.matches(nc) && nc.getArguments().size() > 1) {
                    recordInstanceBased(nc.getArguments().get(1));
                }
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(nc.getType());
                if (fq != null) {
                    acc.directInstantiationsByFqn
                            .computeIfAbsent(fq.getFullyQualifiedName(), k -> new ArrayList<>())
                            .add(new NewClassCallSite(sourcePath()));
                }
                return nc;
            }

            // MethodMatcher's declaring-type matching is too brittle here: `setServiceClass` is
            // typically inherited from an abstract CXF base class that differs across CXF versions
            // (e.g. AbstractServiceFactoryBean), not declared directly on JaxWsServerFactoryBean.
            // Matching on the receiver's own static type is version-hierarchy-independent.
            private boolean isCxfReceiver(J.MethodInvocation m) {
                JavaType receiverType = m.getSelect() != null ? m.getSelect().getType()
                        : (m.getMethodType() != null ? m.getMethodType().getDeclaringType() : null);
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(receiverType);
                return fq != null && fq.getFullyQualifiedName().contains("cxf");
            }

            private void recordClassBased(Expression classLiteral) {
                if (classLiteral instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) classLiteral;
                    if ("class".equals(fa.getSimpleName())) {
                        recordClassBasedByType(fa.getTarget().getType());
                    }
                }
            }

            private void recordClassBasedByType(JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                if (fq != null) {
                    acc.registrationByFqn.computeIfAbsent(fq.getFullyQualifiedName(), k -> new RegistrationEvidence())
                            .classBasedFound = true;
                }
            }

            private void recordInstanceBased(Expression instanceExpr) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(instanceExpr.getType());
                if (fq != null) {
                    acc.registrationByFqn.computeIfAbsent(fq.getFullyQualifiedName(), k -> new RegistrationEvidence())
                            .instanceBasedFound = true;
                }
            }

            private boolean isWebServiceEndpoint(J.ClassDeclaration cd, JavaType.FullyQualified type) {
                if (cd.getLeadingAnnotations().stream().anyMatch(a -> WEB_SERVICE.stream().anyMatch(m -> m.matches(a)))) {
                    return true;
                }
                for (JavaType.FullyQualified iface : type.getInterfaces()) {
                    if (iface.getFullyQualifiedName().endsWith("WSInterface")) {
                        return true;
                    }
                }
                return false;
            }

            private ConstructorShape classifyConstructors(List<J.MethodDeclaration> constructors) {
                if (constructors.isEmpty()) {
                    return ConstructorShape.NONE;
                }
                if (constructors.size() > 1) {
                    return ConstructorShape.OTHER;
                }
                J.MethodDeclaration ctor = constructors.get(0);
                if (hasRealParameters(ctor)) {
                    return ConstructorShape.OTHER;
                }
                List<Statement> body = ctor.getBody() == null ? new ArrayList<>() : ctor.getBody().getStatements();
                if (body.isEmpty()) {
                    return ConstructorShape.TRIVIAL_NO_ARG;
                }
                if (body.size() == 1 && isBareSuperCall(body.get(0))) {
                    return ConstructorShape.TRIVIAL_NO_ARG;
                }
                return ConstructorShape.OTHER;
            }

            private boolean hasRealParameters(J.MethodDeclaration m) {
                List<Statement> params = m.getParameters();
                return !(params.isEmpty() || (params.size() == 1 && params.get(0) instanceof J.Empty));
            }

            private boolean isBareSuperCall(Statement stmt) {
                if (!(stmt instanceof J.MethodInvocation)) {
                    return false;
                }
                J.MethodInvocation mi = (J.MethodInvocation) stmt;
                if (mi.getSelect() != null || !"super".equals(mi.getSimpleName())) {
                    return false;
                }
                List<Expression> args = mi.getArguments();
                return args.isEmpty() || (args.size() == 1 && args.get(0) instanceof J.Empty);
            }

            private String sourcePath() {
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                return cu != null ? cu.getSourcePath().toString() : "unknown";
            }
        };
    }

    // -------------------------------------------------------------------------------------------
    // Phase 2 evaluation (pure function over the fully-populated accumulator)
    // -------------------------------------------------------------------------------------------

    private static ExclusionResult evaluate(ClassInfo candidate, Accumulator acc) {
        if (candidate.hasUnresolvedExtendsClause) {
            return new ExclusionResult(ExclusionReason.A,
                    "Unresolved/external ancestor: " + candidate.extendsClauseText);
        }
        String ancestorFqn = candidate.supertypeFqn;
        while (ancestorFqn != null && !"java.lang.Object".equals(ancestorFqn)) {
            ClassInfo ancestor = acc.classesByFqn.get(ancestorFqn);
            if (ancestor == null) {
                return new ExclusionResult(ExclusionReason.A, "Unresolved/external ancestor: " + ancestorFqn);
            }
            if (ancestor.hasOwnAutowiredOrUninitializedFinalField) {
                return new ExclusionResult(ExclusionReason.A,
                        "Ancestor with its own @Autowired/uninitialized final field: " + ancestorFqn);
            }
            ancestorFqn = ancestor.supertypeFqn;
        }

        if (candidate.isWebServiceEndpoint) {
            RegistrationEvidence ev = acc.registrationByFqn.get(candidate.fqn);
            if (ev != null && ev.classBasedFound) {
                return new ExclusionResult(ExclusionReason.B, "Class-based JAX-WS/CXF registration found");
            }
        }

        List<NewClassCallSite> sites = acc.directInstantiationsByFqn.get(candidate.fqn);
        if (sites != null && !sites.isEmpty()) {
            return new ExclusionResult(ExclusionReason.C, "Direct `new " + simpleName(candidate.fqn) + "(...)` call site(s): " +
                    sites.stream().map(s -> s.location).distinct().collect(Collectors.joining(", ")));
        }

        if (candidate.constructorShape == ConstructorShape.OTHER) {
            return new ExclusionResult(ExclusionReason.D, "Existing constructor(s) do not match the final-field set");
        }

        return null;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    // -------------------------------------------------------------------------------------------
    // Phase 3 transformation (edit)
    // -------------------------------------------------------------------------------------------

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (cd.getKind() != J.ClassDeclaration.Kind.Type.Class) {
                    return cd;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(cd.getType());
                if (type == null) {
                    return cd;
                }
                ClassInfo info = acc.classesByFqn.get(type.getFullyQualifiedName());
                if (info == null || !info.isCandidate()) {
                    return cd;
                }

                ExclusionResult exclusion = evaluate(info, acc);
                if (exclusion != null) {
                    report.insertRow(ctx, new FieldInjectionConversionReport.Row(
                            info.sourcePath, info.fqn, "EXCLUDED", exclusion.reason.name(), exclusion.detail));
                    return withTodoComment(cd, "TODO: Field injection to constructor injection conversion skipped (" +
                            exclusion.reason.name() + "): " + exclusion.detail);
                }

                report.insertRow(ctx, new FieldInjectionConversionReport.Row(
                        info.sourcePath, info.fqn, "CONVERTED", "", ""));
                return convert(cd, info, ctx);
            }

            private J.ClassDeclaration convert(J.ClassDeclaration cd, ClassInfo info, ExecutionContext ctx) {
                List<Statement> statements = cd.getBody().getStatements();
                List<Statement> updated = new ArrayList<>(statements.size());
                boolean trivialCtorPending = info.constructorShape == ConstructorShape.TRIVIAL_NO_ARG;
                for (Statement stmt : statements) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        if (!vd.hasModifier(J.Modifier.Type.Static) &&
                                vd.getLeadingAnnotations().stream().anyMatch(AUTOWIRED::matches)) {
                            updated.add(finalizeField(vd));
                            continue;
                        }
                    } else if (trivialCtorPending && stmt instanceof J.MethodDeclaration &&
                            ((J.MethodDeclaration) stmt).getReturnTypeExpression() == null) {
                        trivialCtorPending = false;
                        continue;
                    }
                    updated.add(stmt);
                }
                cd = cd.withBody(cd.getBody().withStatements(updated));
                maybeRemoveImport("org.springframework.beans.factory.annotation.Autowired");
                updateCursor(cd);

                if (info.hasNoArgsConstructor) {
                    List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                    annotations.removeIf(NO_ARGS_CONSTRUCTOR::matches);
                    cd = cd.withLeadingAnnotations(annotations);
                    maybeRemoveImport("lombok.NoArgsConstructor");
                    updateCursor(cd);
                }

                cd = JavaTemplate.builder("@RequiredArgsConstructor")
                        .imports("lombok.RequiredArgsConstructor")
                        .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "lombok"))
                        .build()
                        .apply(getCursor(), cd.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("lombok.RequiredArgsConstructor", false);
                return cd;
            }

            // Removing the (sole) leading @Autowired annotation leaves whatever comes next — the
            // first modifier, or the type expression if there are no modifiers — carrying its own
            // stale "on the next line" prefix from when it followed the annotation. That prefix must
            // collapse (empty if it's about to be first, a single space if `final` is about to be
            // inserted ahead of it), or the declaration's own top-level prefix plus that stale prefix
            // combine into a spurious blank line / missing space.
            private J.VariableDeclarations finalizeField(J.VariableDeclarations vd) {
                List<J.Annotation> annotations = new ArrayList<>(vd.getLeadingAnnotations());
                boolean hadAnnotations = !annotations.isEmpty();
                annotations.removeIf(AUTOWIRED::matches);
                boolean needsCollapse = hadAnnotations && annotations.isEmpty();
                vd = vd.withLeadingAnnotations(annotations);

                List<J.Modifier> modifiers = new ArrayList<>(vd.getModifiers());
                if (needsCollapse && !modifiers.isEmpty()) {
                    J.Modifier first = modifiers.get(0);
                    modifiers.set(0, first.withPrefix(Space.build("", first.getPrefix().getComments())));
                } else if (needsCollapse) {
                    org.openrewrite.java.tree.TypeTree typeExpr = vd.getTypeExpression();
                    if (typeExpr != null) {
                        vd = vd.withTypeExpression((org.openrewrite.java.tree.TypeTree) typeExpr.withPrefix(
                                Space.build(" ", typeExpr.getPrefix().getComments())));
                    }
                }

                if (!vd.hasModifier(J.Modifier.Type.Final)) {
                    Space finalPrefix = modifiers.isEmpty() ? Space.EMPTY : Space.build(" ", new ArrayList<>());
                    modifiers.add(new J.Modifier(Tree.randomId(), finalPrefix, Markers.EMPTY, null,
                            J.Modifier.Type.Final, new ArrayList<>()));
                }
                return vd.withModifiers(modifiers);
            }

            // Idempotent: attaches the TODO to the class declaration's own top-level prefix (the
            // same mechanism RemoveFieldByType uses for fields), skipping re-insertion if the exact
            // same message is already present.
            private J.ClassDeclaration withTodoComment(J.ClassDeclaration cd, String message) {
                Space prefix = cd.getPrefix();
                String todoText = " " + message;
                for (Comment c : prefix.getComments()) {
                    if (c instanceof TextComment && todoText.equals(((TextComment) c).getText())) {
                        return cd;
                    }
                }
                String indent = lastLineOf(prefix.getWhitespace());
                // Blank line between the TODO and the class declaration, so the flagged class
                // stands out visually instead of reading as if it were part of the comment block.
                TextComment todo = new TextComment(false, todoText, "\n\n" + indent, Markers.EMPTY);
                List<Comment> newComments = new ArrayList<>();
                newComments.add(todo);
                newComments.addAll(prefix.getComments());
                return cd.withPrefix(Space.build(prefix.getWhitespace(), newComments));
            }

            private String lastLineOf(String ws) {
                if (ws == null) {
                    return "";
                }
                int last = ws.lastIndexOf('\n');
                return last >= 0 ? ws.substring(last + 1) : ws;
            }
        };
    }
}
