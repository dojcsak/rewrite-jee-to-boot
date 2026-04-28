package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.AddDependencyVisitor;

/**
 * Adds {@code org.springframework:spring-tx} when Spring {@code @Transactional} is in use
 * but {@code javax.persistence.*} types are not.
 * <p>
 * {@code spring-boot-starter-data-jpa} already provides {@code spring-tx} transitively,
 * so adding it again would be redundant. This recipe only adds {@code spring-tx} when JPA
 * is absent, ensuring every module that uses {@code @Transactional} has the dependency it needs
 * without creating duplicates in JPA modules.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddSpringTxUnlessJpaPresent extends ScanningRecipe<AddSpringTxUnlessJpaPresent.Acc> {

    String displayName = "Add spring-tx if @Transactional is used without JPA";

    String description = "Adds org.springframework:spring-tx when Spring @Transactional is in use " +
            "but javax.persistence.* types are not, avoiding redundancy with spring-boot-starter-data-jpa " +
            "which already provides spring-tx transitively.";

    public static class Acc {
        boolean usesJpa = false;
        boolean usesTransactional = false;
    }

    @Override
    public Acc getInitialValue(ExecutionContext ctx) {
        return new Acc();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Acc acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Import visitImport(J.Import _import, ExecutionContext ctx) {
                String typeName = _import.getTypeName();
                if (typeName.startsWith("javax.persistence.")) {
                    acc.usesJpa = true;
                } else if (typeName.startsWith("org.springframework.transaction.annotation.")) {
                    acc.usesTransactional = true;
                }
                return super.visitImport(_import, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Acc acc) {
        if (!acc.usesTransactional || acc.usesJpa) {
            return TreeVisitor.noop();
        }
        // Spring 5.3.39 is managed by Spring Boot 2.7.18; scope omitted (compile is the Maven default).
        return new AddDependencyVisitor(
                "org.springframework", "spring-tx", "5.3.39",
                null, null, null, null, null, null, null);
    }
}
