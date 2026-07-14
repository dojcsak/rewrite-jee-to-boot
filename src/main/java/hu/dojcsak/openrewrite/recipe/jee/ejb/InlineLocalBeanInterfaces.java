package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveImplements;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Inlines {@code @javax.ejb.Local} EJB business interfaces that have exactly one concrete
 * implementor: every field, parameter, and return-type reference to the interface (including
 * nested inside a generic type argument or array) is retyped to the implementor class (via
 * {@link ChangeType}), the {@code implements} entry is removed from every class that declares it
 * directly (via {@link RemoveImplements}), and - once every reference has been successfully
 * retyped - the now-unused interface source file is deleted.
 * <p>
 * A {@code @Local} candidate is recognized in either form used by real EJB codebases: a bare
 * {@code @Local} directly on the interface declaration, or a class-level
 * {@code @Local({FooLocal.class, ...})} value naming the interface(s) from an (often abstract)
 * bean base class - the same two forms {@link MigrateStatelessSessionBeans} already strips.
 * <p>
 * A candidate is only inlined when: it has exactly one concrete (non-abstract) implementor found
 * in the analyzed source set (searched via the implementor's own {@code implements} list and its
 * whole supertype chain, so an abstract intermediate class that declares {@code implements} on
 * behalf of a subclass is still found correctly); that implementor is not itself skipped by
 * {@link MigrateStatelessSessionBeans#isRemoteBean} (a bean also implementing a {@code @Remote}
 * interface is left entirely alone, consistent with the rest of this recipe suite); no other
 * interface in the source set extends it; every field/parameter/return-type reference to it can be
 * resolved to the implementor class from its own compilation unit's classpath; and no string
 * literal anywhere in the source set contains the interface's simple name (a best-effort heuristic
 * for JNDI-style lookups that {@link ChangeType} cannot see).
 * <p>
 * A reference site's compilation unit is treated as able to resolve the implementor class - and
 * safe to retype - when any of: the implementor's FQN appears directly in that compilation unit's
 * recorded classpath; the interface and its sole implementor are themselves declared in the same
 * Maven/Gradle module (the reference site already proves a working dependency on that module, since
 * it compiles today against the interface declared there - the implementor's own FQN need not itself
 * appear anywhere in the referencing module's source, which is exactly the case for every
 * {@code @Local} call site, by construction, before this recipe runs); or, as a last-resort path
 * heuristic, the referencing compilation unit and the implementor happen to share the same module
 * root. When none of these hold - e.g. a genuine split interface/implementation Maven module layout
 * where the interface and implementor live in two different modules, and the referencing module has
 * a compile dependency only on the module hosting the interface, not the one hosting the implementor
 * - that site is left untouched and flagged with a TODO comment instead, and the interface is not
 * deleted. Either the whole interface is fully inlined and deleted, or the whole interface (and every
 * class's {@code implements} clause naming it) is left exactly as-is except for the TODO-flagged
 * site(s); no partial retyping is attempted.
 * <p>
 * Must be run across the whole multi-module reactor in a single pass: a per-module run can never
 * see a cross-module implementor and would misjudge a used interface as having none. Cross-module
 * reference safety relies on Maven/Gradle-aware parsing (a {@link JavaSourceSet} marker on each
 * compilation unit); when run via plain {@code JavaParser} without project-aware classpath
 * information, this recipe cannot detect cross-module visibility gaps and retypes optimistically.
 * Always rebuild the project immediately after running this recipe to catch anything these
 * heuristics could not see.
 * <p>
 * This recipe's candidate identification depends on {@code @Local} annotations still being
 * physically present in the source; {@link MigrateStatelessSessionBeans} strips them
 * unconditionally. It runs as step 2 of the {@code MigrateStatelessEjb} composite recipe, after
 * {@link MigrateEjbAnnotations} and before {@link MigrateStatelessSessionBeans} strips those
 * annotations - that ordering must be preserved in any custom composition too.
 */
@Slf4j
@Value
@EqualsAndHashCode(callSuper = false)
public class InlineLocalBeanInterfaces extends ScanningRecipe<InlineLocalBeanInterfaces.Acc> {

    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final AnnotationMatcher LOCAL_MATCHER = new AnnotationMatcher("@javax.ejb.Local");

    String displayName = "Inline single-implementor @Local EJB business interfaces";

    String description = "Inlines @javax.ejb.Local business interfaces that have exactly one concrete, " +
            "non-@Remote implementor: retypes every field/parameter/return-type reference (including " +
            "nested inside generics/arrays) to the implementor class, strips the implements clause from " +
            "every class that declares it directly, and deletes the interface once it has zero remaining " +
            "references anywhere in the analyzed source set. Reference sites whose compilation unit cannot " +
            "resolve the implementor class (e.g. a split interface/implementation module layout where the " +
            "referencing module lacks a dependency on the implementation module) are left untouched and " +
            "flagged with a TODO comment instead, and the interface is not deleted in that case. Must run " +
            "across the full multi-module reactor in one pass; always rebuild afterwards to catch anything " +
            "its cross-module visibility heuristics could not see.";

    public static class Acc {
        final Map<String, Path> typeFqnToPath = new HashMap<>();
        final Set<String> localInterfaceCandidateFqns = new HashSet<>();
        final Set<String> extendedInterfaceFqns = new HashSet<>();
        final Map<String, Set<String>> interfaceFqnToImplementorFqns = new HashMap<>();
        final Set<String> remoteImplementorFqns = new HashSet<>();
        final Map<Path, List<String>> cuClasspathFqns = new HashMap<>();
        final Set<Path> cuHasSourceSetMarker = new HashSet<>();
        final Map<String, Set<Path>> fieldParamReturnSitesByInterface = new HashMap<>();
        // Every class (abstract or concrete) whose own `implements` clause names a given interface
        // FQN directly - the implements clause must be stripped from all of them, not only the sole
        // concrete implementor, since an abstract intermediate class may declare it independently.
        final Map<String, Set<Path>> implementsSitesByInterface = new HashMap<>();
        final Set<String> stringLiteralTokens = new HashSet<>();
    }

    @Override
    public Acc getInitialValue(ExecutionContext ctx) {
        return new Acc();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Acc acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                cu.getMarkers().findFirst(JavaSourceSet.class).ifPresent(marker -> {
                    acc.cuHasSourceSetMarker.add(cu.getSourcePath());
                    acc.cuClasspathFqns.put(cu.getSourcePath(), marker.getClasspath().stream()
                            .map(JavaType.FullyQualified::getFullyQualifiedName)
                            .collect(Collectors.toList()));
                });
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(classDecl.getType());
                String fqn = type != null ? type.getFullyQualifiedName() : null;
                if (fqn != null) {
                    acc.typeFqnToPath.put(fqn, cu.getSourcePath());
                }

                if (classDecl.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
                    if (fqn != null && classDecl.getLeadingAnnotations().stream().anyMatch(LOCAL_MATCHER::matches)) {
                        acc.localInterfaceCandidateFqns.add(fqn);
                    }
                    if (classDecl.getImplements() != null) {
                        for (TypeTree superType : classDecl.getImplements()) {
                            JavaType.FullyQualified superFq = TypeUtils.asFullyQualified(superType.getType());
                            if (superFq != null) {
                                acc.extendedInterfaceFqns.add(superFq.getFullyQualifiedName());
                            }
                        }
                    }
                } else {
                    // Any non-interface class's own `implements` clause is a site that needs the
                    // clause stripped once the named interface is fully inlined - independent of
                    // whether this class is abstract or the sole concrete implementor.
                    if (classDecl.getImplements() != null) {
                        for (TypeTree implementedType : classDecl.getImplements()) {
                            JavaType.FullyQualified implementedFq = TypeUtils.asFullyQualified(implementedType.getType());
                            if (implementedFq != null) {
                                acc.implementsSitesByInterface
                                        .computeIfAbsent(implementedFq.getFullyQualifiedName(), k -> new LinkedHashSet<>())
                                        .add(cu.getSourcePath());
                            }
                        }
                    }

                    J.Annotation localAnnotation = classDecl.getLeadingAnnotations().stream()
                            .filter(LOCAL_MATCHER::matches)
                            .findFirst()
                            .orElse(null);
                    if (localAnnotation != null) {
                        List<String> literalFqns = classLiteralFqns(localAnnotation);
                        acc.localInterfaceCandidateFqns.addAll(literalFqns);
                        // The @Local({Foo.class}) class-literal is itself a reference to Foo that
                        // would dangle if Foo were deleted without retyping this annotation value too -
                        // track it exactly like a field/param/return-type site.
                        for (String literalFqn : literalFqns) {
                            recordSite(literalFqn, cu);
                        }
                    }

                    boolean isAbstract = classDecl.getModifiers().stream()
                            .anyMatch(m -> m.getType() == J.Modifier.Type.Abstract);
                    JavaType.Class classType = TypeUtils.asClass(classDecl.getType());
                    if (!isAbstract && classType != null && fqn != null) {
                        Set<String> interfaces = new LinkedHashSet<>();
                        collectAllInterfaces(classType, interfaces);
                        for (String ifaceFqn : interfaces) {
                            acc.interfaceFqnToImplementorFqns
                                    .computeIfAbsent(ifaceFqn, k -> new LinkedHashSet<>())
                                    .add(fqn);
                        }
                        if (MigrateStatelessSessionBeans.isRemoteBean(classDecl, getCursor())) {
                            acc.remoteImplementorFqns.add(fqn);
                        }
                    }
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                recordAllSites(multiVariable.getType());
                return super.visitVariableDeclarations(multiVariable, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (method.getMethodType() != null) {
                    recordAllSites(method.getMethodType().getReturnType());
                }
                return super.visitMethodDeclaration(method, ctx);
            }

            // Records a reference site for every FullyQualified type name reachable within `type`,
            // including nested generic type arguments (List<FooLocal>) and array element types
            // (FooLocal[]) - not just a bare top-level type - so such references aren't invisible to
            // the resolvability/deletion-safety analysis.
            private void recordAllSites(JavaType type) {
                Set<String> fqns = new LinkedHashSet<>();
                collectFullyQualifiedNames(type, fqns);
                if (fqns.isEmpty()) {
                    return;
                }
                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                for (String fqn : fqns) {
                    recordSite(fqn, cu);
                }
            }

            private void recordSite(String fqn, J.CompilationUnit cu) {
                acc.fieldParamReturnSitesByInterface
                        .computeIfAbsent(fqn, k -> new LinkedHashSet<>())
                        .add(cu.getSourcePath());
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, ExecutionContext ctx) {
                if (literal.getValue() instanceof String) {
                    Matcher m = IDENTIFIER_TOKEN.matcher((String) literal.getValue());
                    while (m.find()) {
                        acc.stringLiteralTokens.add(m.group());
                    }
                }
                return super.visitLiteral(literal, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Acc acc) {
        Set<String> inlineCandidates = acc.localInterfaceCandidateFqns.stream()
                .filter(acc.typeFqnToPath::containsKey)
                .filter(fqn -> !acc.extendedInterfaceFqns.contains(fqn))
                .filter(fqn -> acc.interfaceFqnToImplementorFqns.getOrDefault(fqn, Collections.emptySet()).size() == 1)
                .filter(fqn -> !acc.remoteImplementorFqns.contains(soleImplementorOf(fqn, acc)))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> implementorFqnOf = new HashMap<>();
        for (String ifaceFqn : inlineCandidates) {
            implementorFqnOf.put(ifaceFqn, soleImplementorOf(ifaceFqn, acc));
        }

        Map<String, Set<Path>> unresolvableSitesByInterface = new HashMap<>();
        Set<String> fullyResolvedCandidates = new LinkedHashSet<>();
        for (String ifaceFqn : inlineCandidates) {
            String implFqn = implementorFqnOf.get(ifaceFqn);
            Set<Path> referencingCus = acc.fieldParamReturnSitesByInterface.getOrDefault(ifaceFqn, Collections.emptySet());
            Set<Path> unresolvable = referencingCus.stream()
                    .filter(cuPath -> !isResolvableFrom(cuPath, ifaceFqn, implFqn, acc))
                    .collect(Collectors.toSet());
            if (!unresolvable.isEmpty()) {
                unresolvableSitesByInterface.put(ifaceFqn, unresolvable);
            }
            boolean stringLiteralHit = acc.stringLiteralTokens.contains(simpleName(ifaceFqn));
            if (unresolvable.isEmpty() && !stringLiteralHit) {
                fullyResolvedCandidates.add(ifaceFqn);
            } else if (stringLiteralHit) {
                log.warn("Skipped inlining @Local interface '{}': its simple name appears in a string " +
                        "literal elsewhere (possible JNDI lookup) - review and inline manually if unused", ifaceFqn);
            }
        }

        Set<Path> deletablePaths = fullyResolvedCandidates.stream()
                .map(acc.typeFqnToPath::get)
                .collect(Collectors.toSet());

        // Precompute, once, exactly which (candidate) actions apply to each compilation unit -
        // avoids re-scanning the whole fullyResolvedCandidates set for every CU visited below.
        Map<Path, Set<String>> removeImplementsByCu = new HashMap<>();
        Map<Path, Set<String>> changeTypeByCu = new HashMap<>();
        for (String ifaceFqn : fullyResolvedCandidates) {
            String implFqn = implementorFqnOf.get(ifaceFqn);
            Path implPath = acc.typeFqnToPath.get(implFqn);

            Set<Path> implementsSites = new LinkedHashSet<>(
                    acc.implementsSitesByInterface.getOrDefault(ifaceFqn, Collections.emptySet()));
            if (implPath != null) {
                // Safety net: always strip from the sole implementor's own file even if, for some
                // reason, its own `implements` clause wasn't independently recorded above.
                implementsSites.add(implPath);
            }
            for (Path p : implementsSites) {
                removeImplementsByCu.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(ifaceFqn);
            }

            for (Path p : acc.fieldParamReturnSitesByInterface.getOrDefault(ifaceFqn, Collections.emptySet())) {
                changeTypeByCu.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(ifaceFqn);
            }
        }

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    if (deletablePaths.contains(cu.getSourcePath())) {
                        logOnce(ctx, "deleted:" + cu.getSourcePath(), () ->
                                log.warn("Inlined and deleted @Local interface at {}", cu.getSourcePath()));
                        return null;
                    }
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                Path cuPath = cu.getSourcePath();
                for (String ifaceFqn : removeImplementsByCu.getOrDefault(cuPath, Collections.emptySet())) {
                    doAfterVisit(new RemoveImplements(ifaceFqn, null).getVisitor());
                }
                for (String ifaceFqn : changeTypeByCu.getOrDefault(cuPath, Collections.emptySet())) {
                    doAfterVisit(new ChangeType(ifaceFqn, implementorFqnOf.get(ifaceFqn), false).getVisitor());
                }
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations mv = super.visitVariableDeclarations(multiVariable, ctx);
                // Parameters are flagged at the enclosing method declaration instead (see
                // visitMethodDeclaration below): a parameter's own prefix is usually just a single
                // space after a comma/open-paren, not a real newline, so an inline TODO comment
                // there would misplace itself as a trailing comment on the wrong token.
                if (getCursor().getParentTreeCursor().getValue() instanceof J.MethodDeclaration) {
                    return mv;
                }
                for (String declFqn : unresolvableFqnsAtThisSite(mv.getType(), ctx)) {
                    if (!alreadyFlagged(mv.getPrefix(), declFqn)) {
                        mv = flagWithTodoComment(mv, todoMessage(declFqn));
                    }
                }
                return mv;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                List<String> unresolvableFqns = new ArrayList<>();
                if (m.getMethodType() != null) {
                    unresolvableFqns.addAll(unresolvableFqnsAtThisSite(m.getMethodType().getReturnType(), ctx));
                }
                for (Statement param : m.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        unresolvableFqns.addAll(
                                unresolvableFqnsAtThisSite(((J.VariableDeclarations) param).getType(), ctx));
                    }
                }
                for (String declFqn : unresolvableFqns) {
                    if (!alreadyFlagged(m.getPrefix(), declFqn)) {
                        m = flagWithTodoComment(m, todoMessage(declFqn));
                    }
                }
                return m;
            }

            // ScanningRecipe.getVisitor(acc) rebuilds a fresh visitor (from a fresh scan) each of
            // OpenRewrite's 2-3 convergence cycles; since this recipe never retypes an unresolvable
            // site (the field/return type is left exactly as-is), the same site would be judged
            // "unresolvable" again on every cycle and get a duplicate TODO comment without this check.
            private boolean alreadyFlagged(Space prefix, String declFqn) {
                String marker = "could not inline @Local interface '" + declFqn + "'";
                return prefix.getComments().stream()
                        .filter(c -> c instanceof TextComment)
                        .anyMatch(c -> ((TextComment) c).getText().contains(marker));
            }

            private List<String> unresolvableFqnsAtThisSite(JavaType type, ExecutionContext ctx) {
                Set<String> fqns = new LinkedHashSet<>();
                collectFullyQualifiedNames(type, fqns);
                if (fqns.isEmpty()) {
                    return Collections.emptyList();
                }
                Path cuPath = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).getSourcePath();
                List<String> result = new ArrayList<>();
                for (String declFqn : fqns) {
                    Set<Path> unresolvable = unresolvableSitesByInterface.get(declFqn);
                    if (unresolvable != null && unresolvable.contains(cuPath)) {
                        String implFqn = implementorFqnOf.get(declFqn);
                        logOnce(ctx, "unresolvable:" + declFqn + ":" + cuPath, () ->
                                log.warn("Could not inline @Local interface '{}' to '{}' in {}: target class not " +
                                        "visible from this module/classpath", declFqn, implFqn, cuPath));
                        result.add(declFqn);
                    }
                }
                return result;
            }

            private String todoMessage(String declFqn) {
                String implFqn = implementorFqnOf.get(declFqn);
                return "TODO: could not inline @Local interface '" + declFqn + "' to '" + implFqn +
                        "' - target class is not visible from this module/classpath; migrate the field type manually";
            }
        };
    }

    private static String soleImplementorOf(String ifaceFqn, Acc acc) {
        Set<String> implementors = acc.interfaceFqnToImplementorFqns.get(ifaceFqn);
        return implementors == null || implementors.isEmpty() ? null : implementors.iterator().next();
    }

    // Present in that CU's JavaSourceSet classpath; or the interface and implementor are themselves
    // declared in the same module (in which case the reference site already proves a working
    // dependency on that shared module - it resolves the interface today, so it compiles against
    // that module already, regardless of whether the implementor's own FQN happens to appear
    // anywhere in this CU's recorded classpath list, which real-world profiling shows it usually
    // won't: an @Local call site only ever names the interface, never the concrete impl, so the
    // impl's FQN is never lazily loaded into that list even when a genuine compile dependency and
    // real classpath presence exist); or the CU and implementor share the same module root (path
    // heuristic) - else treated as unresolvable. When no JavaSourceSet marker is present at all
    // (e.g. plain JavaParser runs without Maven/Gradle project awareness - which is how this repo's
    // own tests run today), default permissive: this recipe then cannot detect cross-module
    // visibility gaps and retypes optimistically (see class javadoc).
    static boolean isResolvableFrom(Path referencingCuPath, String interfaceFqn, String implementorFqn, Acc acc) {
        if (!acc.cuHasSourceSetMarker.contains(referencingCuPath)) {
            return true;
        }
        Path implPath = acc.typeFqnToPath.get(implementorFqn);
        Path interfacePath = acc.typeFqnToPath.get(interfaceFqn);
        if (interfacePath != null && implPath != null && moduleRoot(interfacePath).equals(moduleRoot(implPath))) {
            return true;
        }
        List<String> classpath = acc.cuClasspathFqns.get(referencingCuPath);
        if (classpath != null && classpath.contains(implementorFqn)) {
            return true;
        }
        return implPath != null && moduleRoot(referencingCuPath).equals(moduleRoot(implPath));
    }

    // Returns the module root by stripping the /src/main/java/ (or /src/test/java/) suffix.
    // Mirrors AddSpringTxUnlessJpaPresent.moduleRoot(Path).
    static String moduleRoot(Path sourcePath) {
        String path = sourcePath.toString().replace('\\', '/');
        int idx = path.indexOf("/src/main/java/");
        if (idx == -1) {
            idx = path.indexOf("/src/test/java/");
        }
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    // Walks the class's own `implements` list and superclass chain, collecting the full set of
    // every interface FQN reachable (including each interface's own superinterfaces) - the reverse
    // shape of MigrateStatelessSessionBeans.implementsRemoteInterface/isRemoteInterface, which only
    // test a boolean predicate rather than accumulating the full set.
    static void collectAllInterfaces(JavaType.Class classType, Set<String> out) {
        if (classType == null || "java.lang.Object".equals(classType.getFullyQualifiedName())) {
            return;
        }
        for (JavaType.FullyQualified iface : classType.getInterfaces()) {
            collectInterfaceAndSupers(iface, out);
        }
        collectAllInterfaces(TypeUtils.asClass(classType.getSupertype()), out);
    }

    private static void collectInterfaceAndSupers(JavaType.FullyQualified iface, Set<String> out) {
        if (iface == null || !out.add(iface.getFullyQualifiedName())) {
            return;
        }
        for (JavaType.FullyQualified superIface : iface.getInterfaces()) {
            collectInterfaceAndSupers(superIface, out);
        }
    }

    // Recursively collects every FullyQualified type name reachable within `type`, unwrapping
    // generic type arguments (List<FooLocal>) and array element types (FooLocal[]) so a candidate
    // interface referenced only nested inside such a type is not invisible to this recipe.
    static void collectFullyQualifiedNames(JavaType type, Set<String> out) {
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized parameterized = (JavaType.Parameterized) type;
            out.add(parameterized.getFullyQualifiedName());
            for (JavaType typeParameter : parameterized.getTypeParameters()) {
                collectFullyQualifiedNames(typeParameter, out);
            }
        } else if (type instanceof JavaType.Array) {
            collectFullyQualifiedNames(((JavaType.Array) type).getElemType(), out);
        } else if (type instanceof JavaType.FullyQualified) {
            out.add(((JavaType.FullyQualified) type).getFullyQualifiedName());
        }
    }

    // Parses the class-literal value(s) of a @Local annotation: @Local(Foo.class),
    // @Local({Foo.class, Bar.class}), and @Local(value = {Foo.class}) are all handled.
    static List<String> classLiteralFqns(J.Annotation annotation) {
        List<String> result = new ArrayList<>();
        for (JavaType.FullyQualified fq : classLiteralTypes(annotation)) {
            result.add(fq.getFullyQualifiedName());
        }
        return result;
    }

    // Same as above but returns the resolved JavaType.FullyQualified itself rather than just its
    // name - MigrateStatelessSessionBeans uses this to tell whether a listed value still names a
    // genuine interface (JavaType.FullyQualified.Kind.Interface) or has already been retyped to a
    // concrete implementor class, without duplicating this annotation-argument-walking logic.
    static List<JavaType.FullyQualified> classLiteralTypes(J.Annotation annotation) {
        if (annotation.getArguments() == null) {
            return Collections.emptyList();
        }
        List<JavaType.FullyQualified> result = new ArrayList<>();
        for (Expression arg : annotation.getArguments()) {
            Expression value = arg;
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                if (!(assignment.getVariable() instanceof J.Identifier) ||
                        !"value".equals(((J.Identifier) assignment.getVariable()).getSimpleName())) {
                    continue;
                }
                value = assignment.getAssignment();
            }
            if (value instanceof J.NewArray) {
                List<Expression> initializer = ((J.NewArray) value).getInitializer();
                if (initializer != null) {
                    for (Expression el : initializer) {
                        addClassLiteralType(el, result);
                    }
                }
            } else {
                addClassLiteralType(value, result);
            }
        }
        return result;
    }

    private static void addClassLiteralType(Expression e, List<JavaType.FullyQualified> out) {
        if (e instanceof J.FieldAccess && "class".equals(((J.FieldAccess) e).getSimpleName())) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(((J.FieldAccess) e).getTarget().getType());
            if (fq != null) {
                out.add(fq);
            }
        }
    }

    private static String simpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    // getVisitor(Acc) reruns fresh every recipe cycle (typically 2-3 per run), so without this guard
    // every warning for a file that's never modified would repeat once per cycle. ExecutionContext
    // is the one piece of state that persists across cycles within a single run.
    private static void logOnce(ExecutionContext ctx, String key, Runnable action) {
        Set<String> logged = ctx.computeMessageIfAbsent(
                "hu.dojcsak.openrewrite.recipe.jee.ejb.InlineLocalBeanInterfaces.logged",
                k -> ConcurrentHashMap.<String>newKeySet());
        if (logged.add(key)) {
            action.run();
        }
    }

    // Adds a TODO comment before the field/method declaration.
    //
    // Unlike MigrateEjbAnnotations.flagWithTodoComment (which flags a field right after removing
    // its @EJB annotation, so the first surviving modifier already carries real leading whitespace
    // of its own), this recipe flags declarations it never otherwise touches - here the leading
    // whitespace lives on the declaration's own top-level prefix, not on the first modifier/type
    // expression (whose own prefix is empty when nothing precedes them). The comment is attached to
    // that top-level prefix, keeping its original whitespace (preserving any blank line before it)
    // and using a single newline + matching indentation as the comment's own suffix so the first
    // modifier/type keyword lands on the next line at the correct indentation.
    private static J.VariableDeclarations flagWithTodoComment(J.VariableDeclarations mv, String message) {
        Space prefix = mv.getPrefix();
        Comment comment = new TextComment(false, " " + message, newlineAndIndent(prefix), Markers.EMPTY);
        List<Comment> comments = new ArrayList<>(prefix.getComments());
        comments.add(comment);
        return mv.withPrefix(prefix.withComments(comments));
    }

    // Same as above but for method declarations (return-type and parameter sites).
    private static J.MethodDeclaration flagWithTodoComment(J.MethodDeclaration m, String message) {
        Space prefix = m.getPrefix();
        Comment comment = new TextComment(false, " " + message, newlineAndIndent(prefix), Markers.EMPTY);
        List<Comment> comments = new ArrayList<>(prefix.getComments());
        comments.add(comment);
        return m.withPrefix(prefix.withComments(comments));
    }

    private static String newlineAndIndent(Space prefix) {
        String ws = prefix.getWhitespace();
        String newline = ws.contains("\r\n") ? "\r\n" : "\n";
        int lastLf = ws.lastIndexOf('\n');
        String indent = lastLf >= 0 ? ws.substring(lastLf + 1).replace("\r", "") : "";
        return newline + indent;
    }
}
