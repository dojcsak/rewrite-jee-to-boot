package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code @EJB} injection (field and setter) with Spring {@code @Autowired}.
 * If the {@code @EJB} annotation carries a {@code beanName} string-literal attribute,
 * a corresponding {@code @Qualifier} annotation is added. Attributes that cannot be
 * automatically migrated ({@code beanInterface}, non-empty {@code name},
 * non-empty {@code mappedName}, non-empty {@code description}) are flagged with TODO
 * comments and {@code @Autowired} is not emitted. A non-literal {@code beanName},
 * {@code lookup}, or {@code mappedName} (constant reference) is also flagged this way.
 * A {@code lookup} attribute is flagged with a TODO comment too, but {@code @Autowired}
 * is still added as long as the declared field/parameter type is not a {@code @Remote}
 * business interface (directly or via superinterface) - Spring resolves {@code @Autowired}
 * by type regardless of the original JNDI lookup name, whereas a {@code @Remote} target needs
 * a manual decision about how to reach it from Spring Boot. Empty-string values for
 * {@code name}, {@code lookup}, and {@code mappedName} are treated as absent (EJB spec default).
 * Constructor-level {@code @EJB} annotations are not processed (EJB does not support
 * constructor injection).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbAnnotations extends Recipe {

    // Matches a Java string literal (to preserve), a block comment, or a line comment (to remove).
    // [\s\S]*? crosses newlines; the string-literal arm prevents stripping /* ... */ that
    // appears inside a quoted annotation attribute value.
    private static final Pattern BLOCK_COMMENT_OR_STRING =
            Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|/\\*[\\s\\S]*?\\*/|//[^\\n\\r]*");

    String displayName = "Replace @EJB injection with @Autowired";

    String description = "Replaces @EJB field and setter injection with Spring @Autowired. " +
            "Adds @Qualifier if the @EJB annotation has a beanName attribute. " +
            "BeanInterface, name, mappedName, and non-empty description attributes are flagged with TODO comments " +
            "and @Autowired is not added. A non-literal beanName, lookup, or mappedName (constant reference) is also " +
            "flagged this way. A lookup attribute is flagged with a TODO comment too, but @Autowired is still added " +
            "unless the declared type is a @Remote business interface (or unresolvable). " +
            "Constructor-level @EJB annotations are not processed.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final AnnotationMatcher ejbMatcher = new AnnotationMatcher("@javax.ejb.EJB");

            @Override
            public J.VariableDeclarations visitVariableDeclarations(
                    J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations mv = super.visitVariableDeclarations(multiVariable, ctx);
                // Skip local variables inside method bodies. Use parent/grandparent check instead of
                // firstEnclosing so that @EJB fields in local classes (ClassDecl inside method body)
                // are still processed — firstEnclosing would incorrectly skip those too.
                Cursor parentCursor = getCursor().getParentTreeCursor();
                if (parentCursor.getValue() instanceof J.Block &&
                        parentCursor.getParentTreeCursor().getValue() instanceof J.MethodDeclaration) {
                    return mv;
                }
                return migrateEjbAnnotation(mv, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(
                    J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

                // Constructors have no return type in the AST; EJB does not support constructor injection.
                if (m.getReturnTypeExpression() == null) {
                    return m;
                }

                J.Annotation ejb = m.getLeadingAnnotations().stream()
                        .filter(ejbMatcher::matches)
                        .findFirst()
                        .orElse(null);
                if (ejb == null) {
                    return m;
                }

                String beanName = getStringAttribute(ejb, "beanName");
                String lookup = getStringAttribute(ejb, "lookup");
                String mappedName = getStringAttribute(ejb, "mappedName");
                // beanInterface is Class-typed, never a J.Literal — use hasAttribute instead of getStringAttribute
                boolean hasBeanInterface = hasAttribute(ejb, "beanInterface");
                boolean hasNonLiteralBeanName = beanName == null && hasAttribute(ejb, "beanName");
                // lookup / mappedName: flag non-empty literal and non-literal (constant reference);
                // empty string is the EJB default and is treated as absent.
                boolean hasNonLiteralLookup = lookup == null && hasAttribute(ejb, "lookup");
                boolean hasNonLiteralMappedName = mappedName == null && hasAttribute(ejb, "mappedName");
                // name is a JNDI environment-namespace alias with no Spring equivalent.
                // Flag only when non-empty literal or non-literal (constant reference);
                // empty string is the EJB default and is treated as absent.
                String ejbName = getStringAttribute(ejb, "name");
                boolean hasEjbNameAttr = StringUtils.isNotEmpty(ejbName) || (ejbName == null && hasAttribute(ejb, "name"));
                String ejbDescription = getStringAttribute(ejb, "description");
                boolean hasEjbDescriptionAttr = StringUtils.isNotEmpty(ejbDescription) ||
                        (ejbDescription == null && hasAttribute(ejb, "description"));
                String ejbAnnotationText = normalizeAnnotationText(ejb.printTrimmed(getCursor()));

                List<J.Annotation> annotations = new ArrayList<>(m.getLeadingAnnotations());
                annotations.removeIf(ejbMatcher::matches);
                m = m.withLeadingAnnotations(annotations);
                maybeRemoveImport("javax.ejb.EJB");
                updateCursor(m);

                boolean hasLookup = StringUtils.isNotEmpty(lookup);
                if (hasBeanInterface || hasNonLiteralBeanName || hasEjbNameAttr ||
                        hasNonLiteralLookup || StringUtils.isNotEmpty(mappedName) || hasNonLiteralMappedName ||
                        hasEjbDescriptionAttr || (hasLookup && lookupBlocksAutowiring(singleParameterType(m)))) {
                    // Cannot auto-migrate — add TODO comment without @Autowired
                    return flagWithTodoComment(m,
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated");
                }

                m = JavaTemplate.builder("@Autowired")
                        .imports("org.springframework.beans.factory.annotation.Autowired")
                        .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "spring-beans"))
                        .build()
                        .apply(getCursor(), m.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.beans.factory.annotation.Autowired", false);

                if (hasLookup) {
                    // lookup has no direct Spring construct, but the declared parameter type is a
                    // local (non-@Remote) interface, so @Autowired resolves it correctly by type.
                    // Keep the TODO comment so the original JNDI lookup stays visible for review.
                    updateCursor(m);
                    m = prependTodoBeforeFirstAnnotation(m,
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated");
                    updateCursor(m);
                }

                if (StringUtils.isNotEmpty(beanName)) {
                    updateCursor(m);
                    String beanNameSource = getStringAttributeSource(ejb, "beanName");
                    if (beanNameSource == null) {
                        beanNameSource = "\"" + beanName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    }
                    m = JavaTemplate.builder("@Qualifier(" + beanNameSource + ")")
                            .imports("org.springframework.beans.factory.annotation.Qualifier")
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "spring-beans"))
                            .build()
                            .apply(getCursor(), m.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.beans.factory.annotation.Qualifier", false);
                }

                return m;
            }

            private J.VariableDeclarations migrateEjbAnnotation(J.VariableDeclarations mv, ExecutionContext ctx) {
                J.Annotation ejb = mv.getLeadingAnnotations().stream()
                        .filter(ejbMatcher::matches)
                        .findFirst()
                        .orElse(null);
                if (ejb == null) {
                    return mv;
                }

                String beanName = getStringAttribute(ejb, "beanName");
                String lookup = getStringAttribute(ejb, "lookup");
                String mappedName = getStringAttribute(ejb, "mappedName");
                // beanInterface is Class-typed, never a J.Literal — use hasAttribute instead of getStringAttribute
                boolean hasBeanInterface = hasAttribute(ejb, "beanInterface");
                boolean hasNonLiteralBeanName = beanName == null && hasAttribute(ejb, "beanName");
                // lookup / mappedName: flag non-empty literal and non-literal (constant reference);
                // empty string is the EJB default and is treated as absent.
                boolean hasNonLiteralLookup = lookup == null && hasAttribute(ejb, "lookup");
                boolean hasNonLiteralMappedName = mappedName == null && hasAttribute(ejb, "mappedName");
                // name is a JNDI environment-namespace alias with no Spring equivalent.
                // Flag only when non-empty literal or non-literal (constant reference);
                // empty string is the EJB default and is treated as absent.
                String ejbName = getStringAttribute(ejb, "name");
                boolean hasEjbNameAttr = StringUtils.isNotEmpty(ejbName) || (ejbName == null && hasAttribute(ejb, "name"));
                String ejbDescription = getStringAttribute(ejb, "description");
                boolean hasEjbDescriptionAttr = StringUtils.isNotEmpty(ejbDescription) ||
                        (ejbDescription == null && hasAttribute(ejb, "description"));
                String ejbAnnotationText = normalizeAnnotationText(ejb.printTrimmed(getCursor()));

                List<J.Annotation> annotations = new ArrayList<>(mv.getLeadingAnnotations());
                annotations.removeIf(ejbMatcher::matches);
                mv = mv.withLeadingAnnotations(annotations);
                maybeRemoveImport("javax.ejb.EJB");
                updateCursor(mv);

                boolean hasLookup = StringUtils.isNotEmpty(lookup);
                if (hasBeanInterface || hasNonLiteralBeanName || hasEjbNameAttr ||
                        hasNonLiteralLookup || StringUtils.isNotEmpty(mappedName) || hasNonLiteralMappedName ||
                        hasEjbDescriptionAttr || (hasLookup && lookupBlocksAutowiring(mv.getType()))) {
                    // Cannot auto-migrate — add TODO comment without @Autowired
                    return flagWithTodoComment(mv,
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated");
                }

                mv = JavaTemplate.builder("@Autowired")
                        .imports("org.springframework.beans.factory.annotation.Autowired")
                        .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "spring-beans"))
                        .build()
                        .apply(getCursor(), mv.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.beans.factory.annotation.Autowired", false);

                if (hasLookup) {
                    // lookup has no direct Spring construct, but the declared field type is a
                    // local (non-@Remote) interface, so @Autowired resolves it correctly by type.
                    // Keep the TODO comment so the original JNDI lookup stays visible for review.
                    updateCursor(mv);
                    mv = prependTodoBeforeFirstAnnotation(mv,
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated");
                    updateCursor(mv);
                }

                if (StringUtils.isNotEmpty(beanName)) {
                    updateCursor(mv);
                    String beanNameSource = getStringAttributeSource(ejb, "beanName");
                    if (beanNameSource == null) {
                        beanNameSource = "\"" + beanName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    }
                    mv = JavaTemplate.builder("@Qualifier(" + beanNameSource + ")")
                            .imports("org.springframework.beans.factory.annotation.Qualifier")
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "spring-beans"))
                            .build()
                            .apply(getCursor(), mv.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.beans.factory.annotation.Qualifier", false);
                }

                return mv;
            }

            // Adds a TODO comment before the field, used only when @Autowired is NOT going to be
            // added (the field has no leading annotations at this point). The leading whitespace of
            // the first token (modifier or type expression) becomes the comment's suffix so the field
            // stays on the next line at the correct indentation; that same whitespace is also what the
            // declaration's own top-level prefix already accounts for, so it is zeroed out here to
            // avoid a doubled, blank-line-producing newline. Falls back to no-op only when neither
            // modifiers nor a type expression are present, which cannot occur in valid Java source.
            private J.VariableDeclarations flagWithTodoComment(J.VariableDeclarations mv, String message) {
                List<J.Modifier> modifiers = mv.getModifiers();
                if (!modifiers.isEmpty()) {
                    J.Modifier first = modifiers.get(0);
                    org.openrewrite.java.tree.Space modPrefix = first.getPrefix();
                    org.openrewrite.java.tree.Comment comment = new org.openrewrite.java.tree.TextComment(
                            false, " " + message,
                            modPrefix.getWhitespace(),
                            org.openrewrite.marker.Markers.EMPTY);
                    List<org.openrewrite.java.tree.Comment> comments = new ArrayList<>(modPrefix.getComments());
                    comments.add(comment);
                    List<J.Modifier> newModifiers = new ArrayList<>(modifiers);
                    newModifiers.set(0, first.withPrefix(modPrefix.withComments(comments).withWhitespace("")));
                    return mv.withModifiers(newModifiers);
                }
                // Package-private field: no modifiers, attach comment to the type expression prefix.
                TypeTree typeExpr = mv.getTypeExpression();
                if (typeExpr != null) {
                    org.openrewrite.java.tree.Space typePrefix = typeExpr.getPrefix();
                    org.openrewrite.java.tree.Comment comment = new org.openrewrite.java.tree.TextComment(
                            false, " " + message,
                            typePrefix.getWhitespace(),
                            org.openrewrite.marker.Markers.EMPTY);
                    List<org.openrewrite.java.tree.Comment> comments = new ArrayList<>(typePrefix.getComments());
                    comments.add(comment);
                    return mv.withTypeExpression((TypeTree) typeExpr.withPrefix(
                            typePrefix.withComments(comments).withWhitespace("")));
                }
                return mv;
            }

            // Same as above but for method declarations (setter injection).
            private J.MethodDeclaration flagWithTodoComment(J.MethodDeclaration m, String message) {
                List<J.Modifier> modifiers = m.getModifiers();
                if (!modifiers.isEmpty()) {
                    J.Modifier first = modifiers.get(0);
                    org.openrewrite.java.tree.Space modPrefix = first.getPrefix();
                    org.openrewrite.java.tree.Comment comment = new org.openrewrite.java.tree.TextComment(
                            false, " " + message,
                            modPrefix.getWhitespace(),
                            org.openrewrite.marker.Markers.EMPTY);
                    List<org.openrewrite.java.tree.Comment> comments = new ArrayList<>(modPrefix.getComments());
                    comments.add(comment);
                    List<J.Modifier> newModifiers = new ArrayList<>(modifiers);
                    newModifiers.set(0, first.withPrefix(modPrefix.withComments(comments).withWhitespace("")));
                    return m.withModifiers(newModifiers);
                }
                // Package-private method: no modifiers, attach comment to the return type prefix.
                TypeTree returnType = m.getReturnTypeExpression();
                if (returnType != null) {
                    org.openrewrite.java.tree.Space returnPrefix = returnType.getPrefix();
                    org.openrewrite.java.tree.Comment comment = new org.openrewrite.java.tree.TextComment(
                            false, " " + message,
                            returnPrefix.getWhitespace(),
                            org.openrewrite.marker.Markers.EMPTY);
                    List<org.openrewrite.java.tree.Comment> comments = new ArrayList<>(returnPrefix.getComments());
                    comments.add(comment);
                    return m.withReturnTypeExpression((TypeTree) returnType.withPrefix(
                            returnPrefix.withComments(comments).withWhitespace("")));
                }
                return m;
            }

            // Adds a TODO comment before the first leading annotation (@Autowired, added despite a
            // lookup attribute), so the comment reads above the code it explains instead of below it -
            // consistent with how MigrateStatelessSessionBeans flags its own TODOs before @Service.
            // JavaTemplate gives a freshly inserted sole annotation an empty prefix (the indentation
            // before it comes from the declaration's own top-level prefix instead), so the comment's
            // suffix is rebuilt from just that prefix's indentation (not its full whitespace, which
            // for a member after a blank-line gap would otherwise reproduce the blank line a second
            // time between the comment and the annotation).
            private J.VariableDeclarations prependTodoBeforeFirstAnnotation(J.VariableDeclarations mv, String message) {
                List<J.Annotation> annotations = mv.getLeadingAnnotations();
                J.Annotation first = annotations.get(0);
                org.openrewrite.java.tree.Space prefix = first.getPrefix();
                org.openrewrite.java.tree.Comment comment = new org.openrewrite.java.tree.TextComment(
                        false, " " + message,
                        singleLineIndentSuffix(mv.getPrefix()),
                        org.openrewrite.marker.Markers.EMPTY);
                List<org.openrewrite.java.tree.Comment> comments = new ArrayList<>(prefix.getComments());
                comments.add(comment);
                List<J.Annotation> newAnnotations = new ArrayList<>(annotations);
                newAnnotations.set(0, first.withPrefix(prefix.withComments(comments)));
                return mv.withLeadingAnnotations(newAnnotations);
            }

            // Same as above but for method declarations (setter injection).
            private J.MethodDeclaration prependTodoBeforeFirstAnnotation(J.MethodDeclaration m, String message) {
                List<J.Annotation> annotations = m.getLeadingAnnotations();
                J.Annotation first = annotations.get(0);
                org.openrewrite.java.tree.Space prefix = first.getPrefix();
                org.openrewrite.java.tree.Comment comment = new org.openrewrite.java.tree.TextComment(
                        false, " " + message,
                        singleLineIndentSuffix(m.getPrefix()),
                        org.openrewrite.marker.Markers.EMPTY);
                List<org.openrewrite.java.tree.Comment> comments = new ArrayList<>(prefix.getComments());
                comments.add(comment);
                List<J.Annotation> newAnnotations = new ArrayList<>(annotations);
                newAnnotations.set(0, first.withPrefix(prefix.withComments(comments)));
                return m.withLeadingAnnotations(newAnnotations);
            }

            // A single newline (matching the declaration's own line ending style) plus its indentation -
            // i.e. declPrefix's whitespace with any leading blank-line newlines collapsed to one.
            private String singleLineIndentSuffix(org.openrewrite.java.tree.Space declPrefix) {
                String newline = declPrefix.getWhitespace().contains("\r\n") ? "\r\n" : "\n";
                return newline + declPrefix.getIndent();
            }

            // A lookup value has no direct Spring construct (arbitrary JNDI name), so it falls back to
            // by-type autowiring: Spring resolves @Autowired by the declared type regardless of the
            // original JNDI name. That is unsafe when the declared type is a @Remote business
            // interface - @Remote signals the target was designed for out-of-process access and
            // needs a manual decision (REST client, messaging, etc.) rather than same-JVM autowiring -
            // or when the type cannot be resolved at all.
            private boolean lookupBlocksAutowiring(JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                return fq == null || isRemoteInterface(fq);
            }

            // Walks the interface's own superinterface hierarchy so @Remote is detected whether
            // it's declared directly or inherited (e.g. FooRemote extends @Remote BaseRemote).
            private boolean isRemoteInterface(JavaType.FullyQualified iface) {
                if (iface.getAnnotations().stream()
                        .anyMatch(a -> "javax.ejb.Remote".equals(a.getFullyQualifiedName()))) {
                    return true;
                }
                return iface.getInterfaces().stream().anyMatch(this::isRemoteInterface);
            }

            // Setter injection's target type is its single parameter's declared type.
            private JavaType singleParameterType(J.MethodDeclaration m) {
                List<Statement> params = m.getParameters();
                if (params.size() == 1 && params.get(0) instanceof J.VariableDeclarations) {
                    return ((J.VariableDeclarations) params.get(0)).getType();
                }
                return null;
            }

            // Strips block and line comments and normalises whitespace/parens in the annotation's
            // printed text for use in a TODO message. String literals are replaced with index tokens
            // bracketed by SOH+STX (chars 1 and 2) before comments are removed and whitespace
            // normalised globally, then restored afterwards. The 5-char sequence
            // SOH STX <digit(s)> STX SOH is collision-free in practice: even if a raw SOH byte
            // appears in a string literal's source, it cannot form the full 5-char bracket without
            // an adjacent STX. The single global normalisation step collapses whitespace across
            // comment-removal gaps: "@EJB(/* x */ lookup" → "@EJB(lookup".
            private String normalizeAnnotationText(String text) {
                final char SOH = 1;
                final char STX = 2;
                List<String> literals = new ArrayList<>();
                Matcher m = BLOCK_COMMENT_OR_STRING.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    if (m.group().startsWith("\"")) {
                        m.appendReplacement(sb, Matcher.quoteReplacement(
                                "" + SOH + STX + literals.size() + STX + SOH));
                        literals.add(m.group());
                    } else {
                        m.appendReplacement(sb, "");
                    }
                }
                m.appendTail(sb);

                String normalized = sb.toString()
                        .replaceAll("\\s+", " ")
                        .replaceAll("\\s*\\(\\s*", "(")
                        .replaceAll("\\s*\\)", ")")
                        .trim();

                for (int i = 0; i < literals.size(); i++) {
                    normalized = normalized.replace("" + SOH + STX + i + STX + SOH, literals.get(i));
                }
                return normalized;
            }
        };
    }

    static String getStringAttribute(J.Annotation annotation, String attributeName) {
        if (annotation.getArguments() == null) {
            return null;
        }
        for (Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                if (assignment.getVariable() instanceof J.Identifier) {
                    String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                    if (attributeName.equals(name) && assignment.getAssignment() instanceof J.Literal) {
                        Object value = ((J.Literal) assignment.getAssignment()).getValue();
                        return value != null ? value.toString() : null;
                    }
                }
            }
        }
        return null;
    }

    // Returns the source form of a string-literal attribute (e.g. "myBean" including quotes),
    // safe to embed directly in a JavaTemplate string without re-escaping.
    static String getStringAttributeSource(J.Annotation annotation, String attributeName) {
        if (annotation.getArguments() == null) {
            return null;
        }
        for (Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                if (assignment.getVariable() instanceof J.Identifier) {
                    String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                    if (attributeName.equals(name) && assignment.getAssignment() instanceof J.Literal) {
                        return ((J.Literal) assignment.getAssignment()).getValueSource();
                    }
                }
            }
        }
        return null;
    }

    // Returns true if the named attribute is present in the annotation, regardless of whether
    // its value is a string literal or a non-literal expression (e.g. a constant reference).
    static boolean hasAttribute(J.Annotation annotation, String attributeName) {
        if (annotation.getArguments() == null) {
            return false;
        }
        for (Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                if (assignment.getVariable() instanceof J.Identifier) {
                    String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                    if (attributeName.equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
