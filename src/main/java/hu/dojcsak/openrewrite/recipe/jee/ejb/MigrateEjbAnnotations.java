package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
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

    @Override
    public String getDisplayName() {
        return "Replace @EJB injection with @Autowired";
    }

    @Override
    public String getDescription() {
        return "Replaces @EJB field and setter injection with Spring @Autowired. " +
               "Adds @Qualifier if the @EJB annotation has a beanName attribute. " +
               "Lookup and beanInterface attributes are flagged with TODO comments.";
    }

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

                m = JavaTemplate.builder("@Autowired")
                        .imports("org.springframework.beans.factory.annotation.Autowired")
                        .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                        .build()
                        .apply(getCursor(), m.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.beans.factory.annotation.Autowired", false);

                if (beanName != null && !beanName.isEmpty()) {
                    updateCursor(m);
                    m = JavaTemplate.builder("@Qualifier(\"" + beanName + "\")")
                            .imports("org.springframework.beans.factory.annotation.Qualifier")
                            .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                            .build()
                            .apply(getCursor(), m.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.beans.factory.annotation.Qualifier", false);
                }

                if (lookup != null) {
                    m = m.withLeadingAnnotations(addTodoComment(m.getLeadingAnnotations(),
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated"));
                }
                if (beanInterface != null) {
                    m = m.withLeadingAnnotations(addTodoComment(m.getLeadingAnnotations(),
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated"));
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

                mv = JavaTemplate.builder("@Autowired")
                        .imports("org.springframework.beans.factory.annotation.Autowired")
                        .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                        .build()
                        .apply(getCursor(), mv.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.beans.factory.annotation.Autowired", false);

                if (beanName != null && !beanName.isEmpty()) {
                    updateCursor(mv);
                    mv = JavaTemplate.builder("@Qualifier(\"" + beanName + "\")")
                            .imports("org.springframework.beans.factory.annotation.Qualifier")
                            .javaParser(JavaParser.fromJavaVersion().classpath("spring-beans"))
                            .build()
                            .apply(getCursor(), mv.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.beans.factory.annotation.Qualifier", false);
                }

                if (lookup != null) {
                    mv = mv.withLeadingAnnotations(addTodoComment(mv.getLeadingAnnotations(),
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated"));
                }
                if (beanInterface != null) {
                    mv = mv.withLeadingAnnotations(addTodoComment(mv.getLeadingAnnotations(),
                            "TODO: " + ejbAnnotationText + " could not be automatically migrated"));
                }

                return mv;
            }

            private String normalizeAnnotationText(String text) {
                return text.replaceAll("\\s+", " ")
                           .replaceAll("\\s*\\(\\s*", "(")
                           .replaceAll("\\s*\\)", ")")
                           .trim();
            }

            private List<J.Annotation> addTodoComment(List<J.Annotation> annotations, String message) {
                if (annotations.isEmpty()) {
                    return annotations;
                }
                J.Annotation first = annotations.get(0);
                org.openrewrite.java.tree.Space space = first.getPrefix();
                List<org.openrewrite.marker.Markers> noMarkers = java.util.Collections.emptyList();
                org.openrewrite.java.tree.Comment comment = new org.openrewrite.java.tree.TextComment(
                        false, " " + message + " ", "", org.openrewrite.marker.Markers.EMPTY);
                List<org.openrewrite.java.tree.Comment> newComments = new ArrayList<>(space.getComments());
                newComments.add(comment);
                first = first.withPrefix(space.withComments(newComments));
                List<J.Annotation> result = new ArrayList<>(annotations);
                result.set(0, first);
                return result;
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
