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
import org.openrewrite.java.tree.TypeTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code @EJB} injection (field and setter) with Spring {@code @Autowired}.
 * If the {@code @EJB} annotation carries a {@code beanName} string-literal attribute,
 * a corresponding {@code @Qualifier} annotation is added. Attributes that cannot be
 * automatically migrated ({@code lookup}, {@code beanInterface}, non-empty {@code name},
 * non-empty {@code mappedName}, non-empty {@code description}) are flagged with TODO
 * comments. A non-literal {@code beanName}, {@code lookup}, or {@code mappedName}
 * (constant reference) is also flagged. Empty-string values for {@code name},
 * {@code lookup}, and {@code mappedName} are treated as absent (EJB spec default).
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
            "Lookup, beanInterface, name, mappedName, and non-empty description attributes are flagged with TODO comments. " +
            "A non-literal beanName, lookup, or mappedName (constant reference) is also flagged. " +
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
                return migrateEjbAnnotation(mv);
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

                if (StringUtils.isNotEmpty(lookup) || hasBeanInterface || hasNonLiteralBeanName || hasEjbNameAttr ||
                        hasNonLiteralLookup || StringUtils.isNotEmpty(mappedName) || hasNonLiteralMappedName ||
                        hasEjbDescriptionAttr) {
                    // Cannot auto-migrate — add TODO comment without @Autowired
                    return flagWithTodoComment(m,
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated");
                }

                m = JavaTemplate.builder("@Autowired")
                        .imports("org.springframework.beans.factory.annotation.Autowired")
                        .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                        .build()
                        .apply(getCursor(), m.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.beans.factory.annotation.Autowired", false);

                if (StringUtils.isNotEmpty(beanName)) {
                    updateCursor(m);
                    String beanNameSource = getStringAttributeSource(ejb, "beanName");
                    if (beanNameSource == null) {
                        beanNameSource = "\"" + beanName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    }
                    m = JavaTemplate.builder("@Qualifier(" + beanNameSource + ")")
                            .imports("org.springframework.beans.factory.annotation.Qualifier")
                            .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                            .build()
                            .apply(getCursor(), m.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.beans.factory.annotation.Qualifier", false);
                }

                return m;
            }

            private J.VariableDeclarations migrateEjbAnnotation(J.VariableDeclarations mv) {
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

                if (StringUtils.isNotEmpty(lookup) || hasBeanInterface || hasNonLiteralBeanName || hasEjbNameAttr ||
                        hasNonLiteralLookup || StringUtils.isNotEmpty(mappedName) || hasNonLiteralMappedName ||
                        hasEjbDescriptionAttr) {
                    // Cannot auto-migrate — add TODO comment without @Autowired
                    return flagWithTodoComment(mv,
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated");
                }

                mv = JavaTemplate.builder("@Autowired")
                        .imports("org.springframework.beans.factory.annotation.Autowired")
                        .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                        .build()
                        .apply(getCursor(), mv.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.beans.factory.annotation.Autowired", false);

                if (StringUtils.isNotEmpty(beanName)) {
                    updateCursor(mv);
                    String beanNameSource = getStringAttributeSource(ejb, "beanName");
                    if (beanNameSource == null) {
                        beanNameSource = "\"" + beanName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    }
                    mv = JavaTemplate.builder("@Qualifier(" + beanNameSource + ")")
                            .imports("org.springframework.beans.factory.annotation.Qualifier")
                            .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                            .build()
                            .apply(getCursor(), mv.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.beans.factory.annotation.Qualifier", false);
                }

                return mv;
            }

            // Adds a TODO comment before the field. The leading whitespace of the first token
            // (modifier or type expression) is moved to the comment's suffix so the field stays
            // on the next line at the correct indentation with no extra blank lines.
            // Falls back to no-op only when neither modifiers nor a type expression are present,
            // which cannot occur in valid Java source.
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
