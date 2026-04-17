package hu.dojcsak.openrewrite.recipe.jee.tx;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateTransactionAnnotationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateTransactionAnnotations())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("javax.ejb-api", "spring-tx"));
    }

    // -------------------------------------------------------------------------
    // @TransactionManagement removal
    // -------------------------------------------------------------------------

    @Test
    void removesTransactionManagement() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionManagement;
                import javax.ejb.TransactionManagementType;

                @TransactionManagement(TransactionManagementType.CONTAINER)
                class OrderService {}
                """,
                """
                class OrderService {}
                """
        ));
    }

    // -------------------------------------------------------------------------
    // @TransactionAttribute on class — default (REQUIRED)
    // -------------------------------------------------------------------------

    @Test
    void replacesClassLevelTransactionAttributeRequired() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                @TransactionAttribute(TransactionAttributeType.REQUIRED)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                @Transactional
                class OrderService {}
                """
        ));
    }

    @Test
    void replacesClassLevelTransactionAttributeNoArgument() {
        // @TransactionAttribute without argument defaults to REQUIRED
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;

                @TransactionAttribute
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                @Transactional
                class OrderService {}
                """
        ));
    }

    // -------------------------------------------------------------------------
    // @TransactionAttribute on class — non-REQUIRED propagation
    // -------------------------------------------------------------------------

    @Test
    void replacesClassLevelTransactionAttributeRequiresNew() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional(propagation = Propagation.REQUIRES_NEW)
                class OrderService {}
                """
        ));
    }

    @Test
    void replacesClassLevelTransactionAttributeMandatory() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                @TransactionAttribute(TransactionAttributeType.MANDATORY)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional(propagation = Propagation.MANDATORY)
                class OrderService {}
                """
        ));
    }

    @Test
    void replacesClassLevelTransactionAttributeNever() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                @TransactionAttribute(TransactionAttributeType.NEVER)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional(propagation = Propagation.NEVER)
                class OrderService {}
                """
        ));
    }

    @Test
    void replacesClassLevelTransactionAttributeNotSupported() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional(propagation = Propagation.NOT_SUPPORTED)
                class OrderService {}
                """
        ));
    }

    @Test
    void replacesClassLevelTransactionAttributeSupports() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                @TransactionAttribute(TransactionAttributeType.SUPPORTS)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional(propagation = Propagation.SUPPORTS)
                class OrderService {}
                """
        ));
    }

    // -------------------------------------------------------------------------
    // @TransactionAttribute on class — replaces existing @Transactional
    // -------------------------------------------------------------------------

    @Test
    void replacesExistingTransactionalOnClass() {
        // If the class already has @Transactional (e.g. from a previous migration step)
        // and also has @TransactionAttribute, the @Transactional is replaced.
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional
                @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional(propagation = Propagation.REQUIRES_NEW)
                class OrderService {}
                """
        ));
    }

    // -------------------------------------------------------------------------
    // @TransactionAttribute on method
    // -------------------------------------------------------------------------

    @Test
    void replacesMethodLevelTransactionAttributeRequired() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                class OrderService {
                    @TransactionAttribute(TransactionAttributeType.REQUIRED)
                    void placeOrder() {}
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                class OrderService {
                    @Transactional
                    void placeOrder() {}
                }
                """
        ));
    }

    @Test
    void replacesMethodLevelTransactionAttributeRequiresNew() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                class OrderService {
                    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
                    void placeOrder() {}
                }
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                class OrderService {
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    void placeOrder() {}
                }
                """
        ));
    }

    // -------------------------------------------------------------------------
    // Combined: both @TransactionManagement and @TransactionAttribute
    // -------------------------------------------------------------------------

    @Test
    void handlesBothAnnotationsOnClass() {
        rewriteRun(java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;
                import javax.ejb.TransactionManagement;
                import javax.ejb.TransactionManagementType;

                @TransactionManagement(TransactionManagementType.CONTAINER)
                @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
                class OrderService {}
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Transactional(propagation = Propagation.REQUIRES_NEW)
                class OrderService {}
                """
        ));
    }

    // -------------------------------------------------------------------------
    // No-op
    // -------------------------------------------------------------------------

    @Test
    void doesNotModifyClassWithoutTransactionAnnotations() {
        rewriteRun(java(
                """
                class OrderService {
                    void placeOrder() {}
                }
                """
        ));
    }
}
