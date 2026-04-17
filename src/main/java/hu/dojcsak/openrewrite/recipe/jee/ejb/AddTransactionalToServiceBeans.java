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
import org.openrewrite.java.tree.J;

import java.util.Comparator;

/**
 * Adds {@code @Transactional} to Spring {@code @Service} classes that do not already have it,
 * as a replacement for EJB Container-Managed Transactions (CMT).
 * EJBs have CMT enabled by default; the equivalent in Spring is explicit {@code @Transactional}.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddTransactionalToServiceBeans extends Recipe {

    @Override
    public String getDisplayName() {
        return "Add @Transactional to @Service beans";
    }

    @Override
    public String getDescription() {
        return "Adds @Transactional to Spring @Service classes that do not already have it, " +
               "as a replacement for EJB Container-Managed Transactions (CMT).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final AnnotationMatcher serviceMatcher =
                    new AnnotationMatcher("@org.springframework.stereotype.Service");
            private final AnnotationMatcher transactionalMatcher =
                    new AnnotationMatcher("@org.springframework.transaction.annotation.Transactional");

            @Override
            public J.ClassDeclaration visitClassDeclaration(
                    J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                if (cd.getLeadingAnnotations().stream().noneMatch(serviceMatcher::matches)) {
                    return cd;
                }
                if (cd.getLeadingAnnotations().stream().anyMatch(transactionalMatcher::matches)) {
                    return cd;
                }

                cd = JavaTemplate.builder("@Transactional")
                        .imports("org.springframework.transaction.annotation.Transactional")
                        .javaParser(JavaParser.fromJavaVersion().classpath("spring-tx"))
                        .build()
                        .apply(getCursor(), cd.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.transaction.annotation.Transactional", false);

                return cd;
            }
        };
    }
}
