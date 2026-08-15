package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class AddTransactionalToServiceBeansTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddTransactionalToServiceBeans())
                .parser(JavaParser.fromJavaVersion().classpath("spring-context", "spring-tx"));
    }

    @DocumentExample
    @Test
    void addsTransactionalToServiceClass() {
        rewriteRun(
                java(
                        """
                                import org.springframework.stereotype.Service;

                                @Service
                                class OrderService {
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;
                                import org.springframework.transaction.annotation.Transactional;

                                @Service
                                @Transactional
                                class OrderService {
                                }
                                """
                )
        );
    }

    @Test
    void skipsClassWithoutServiceAnnotation() {
        rewriteRun(
                java(
                        """
                                class OrderService {
                                }
                                """
                )
        );
    }

    @Test
    void skipsServiceClassThatAlreadyHasTransactional() {
        rewriteRun(
                java(
                        """
                                import org.springframework.stereotype.Service;
                                import org.springframework.transaction.annotation.Transactional;

                                @Service
                                @Transactional
                                class OrderService {
                                }
                                """
                )
        );
    }

    // A manually indented multi-line extends/implements layout (common in Andromda-generated EJB
    // code) should survive @Transactional insertion unchanged. Confirmed via the real
    // rewrite-maven-plugin execution path (against a real multi-module project) that
    // JavaTemplate.apply()'s default autoFormat pass could otherwise collapse this onto an unindented
    // single line even though only a leading annotation is being added - fixed by snapshotting
    // cd.getPadding().getExtends()/getImplements() before the apply() call above and restoring them
    // after. That regression is not reproducible under this test harness's plain JavaParser, so this
    // test documents the desired property rather than guarding the specific defect.
    @Test
    void preservesExtendsImplementsIndentationWhenAddingTransactional() {
        rewriteRun(
                java(
                        """
                                public class OrderServiceBase {
                                }
                                """
                ),
                java(
                        """
                                public interface OrderServiceInterface {
                                }
                                """
                ),
                java(
                        """
                                import org.springframework.stereotype.Service;

                                @Service
                                public class OrderService
                                        extends OrderServiceBase
                                        implements OrderServiceInterface {
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;
                                import org.springframework.transaction.annotation.Transactional;

                                @Service
                                @Transactional
                                public class OrderService
                                        extends OrderServiceBase
                                        implements OrderServiceInterface {
                                }
                                """
                )
        );
    }
}
