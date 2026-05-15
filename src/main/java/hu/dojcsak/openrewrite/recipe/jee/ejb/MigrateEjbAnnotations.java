package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replaces {@code @EJB} injection (field and setter) with Spring {@code @Autowired}.
 * If the {@code @EJB} annotation carries a {@code beanName} attribute, a corresponding
 * {@code @Qualifier} annotation is added. Attributes that cannot be automatically
 * migrated ({@code lookup}, {@code beanInterface}) are flagged with TODO comments.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbAnnotations extends Recipe {

    String displayName = "Replace @EJB injection with @Autowired";

    String description = "Replaces @EJB field and setter injection with Spring @Autowired. " +
            "Adds @Qualifier if the @EJB annotation has a beanName attribute. " +
            "Lookup and beanInterface attributes are flagged with TODO comments.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final AnnotationMatcher ejbMatcher = new AnnotationMatcher("@javax.ejb.EJB");

            @Override
            public J.VariableDeclarations visitVariableDeclarations(
                    J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations mv = super.visitVariableDeclarations(multiVariable, ctx);
                return migrateEjbAnnotation(mv);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(
                    J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

                J.Annotation ejb = m.getLeadingAnnotations().stream()
                        .filter(ejbMatcher::matches)
                        .findFirst()
                        .orElse(null);
                if (ejb == null) {
                    return m;
                }

                String beanName = getStringAttribute(ejb, "beanName");
                String lookup   = getStringAttribute(ejb, "lookup");
                String beanInterface = getStringAttribute(ejb, "beanInterface");
                String ejbAnnotationText = normalizeAnnotationText(ejb.printTrimmed(getCursor()));

                List<J.Annotation> annotations = new ArrayList<>(m.getLeadingAnnotations());
                annotations.removeIf(ejbMatcher::matches);
                m = m.withLeadingAnnotations(annotations);
                maybeRemoveImport("javax.ejb.EJB");
                updateCursor(m);

                if (lookup != null || beanInterface != null) {
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
                    m = JavaTemplate.builder("@Qualifier(\"" + beanName + "\")")
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
                String lookup   = getStringAttribute(ejb, "lookup");
                String beanInterface = getStringAttribute(ejb, "beanInterface");
                String ejbAnnotationText = normalizeAnnotationText(ejb.printTrimmed(getCursor()));

                List<J.Annotation> annotations = new ArrayList<>(mv.getLeadingAnnotations());
                annotations.removeIf(ejbMatcher::matches);
                mv = mv.withLeadingAnnotations(annotations);
                maybeRemoveImport("javax.ejb.EJB");
                updateCursor(mv);

                if (lookup != null || beanInterface != null) {
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
                    mv = JavaTemplate.builder("@Qualifier(\"" + beanName + "\")")
                            .imports("org.springframework.beans.factory.annotation.Qualifier")
                            .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                            .build()
                            .apply(getCursor(), mv.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.beans.factory.annotation.Qualifier", false);
                }

                return mv;
            }

            // Adds a TODO comment before the field (on the modifier's prefix, e.g. "private").
            // Does not add @Autowired — used when auto-migration is not possible (lookup/beanInterface).
            // The modifier's prefix whitespace is moved to the comment's suffix so the field remains
            // on the next line at the correct indentation, with no extra blank lines.
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
                return m;
            }

            private String normalizeAnnotationText(String text) {
                return text.replaceAll("\\s+", " ")
                           .replaceAll("\\s*\\(\\s*", "(")
                           .replaceAll("\\s*\\)", ")")
                           .trim();
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
}
