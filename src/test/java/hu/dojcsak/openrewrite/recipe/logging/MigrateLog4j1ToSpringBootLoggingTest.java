package hu.dojcsak.openrewrite.recipe.logging;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateLog4j1ToSpringBootLoggingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("hu.dojcsak.openrewrite.recipe.MigrateLog4j1ToSpringBootLogging")
                .parser(JavaParser.fromJavaVersion().classpath("log4j", "slf4j-api"));
    }

    // -------------------------------------------------------------------------
    // Java: Log4j 1.x → SLF4J
    // -------------------------------------------------------------------------

    @Test
    void migratesLog4j1LoggerToSlf4j() {
        rewriteRun(java(
                """
                import org.apache.log4j.Logger;

                class OrderService {
                    private static final Logger log = Logger.getLogger(OrderService.class);

                    void placeOrder() {
                        log.info("Placing order");
                        log.warn("Low stock");
                        log.error("Failed", new RuntimeException("oops"));
                    }
                }
                """,
                """
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class OrderService {
                    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

                    void placeOrder() {
                        log.info("Placing order");
                        log.warn("Low stock");
                        log.error("Failed", new RuntimeException("oops"));
                    }
                }
                """
        ));
    }

    @Test
    void fixesLoggerNamedForWrongClass() {
        // String.class is always resolvable without extra classpath entries
        rewriteRun(java(
                """
                import org.apache.log4j.Logger;

                class PaymentService {
                    private static final Logger log = Logger.getLogger(String.class);
                }
                """,
                """
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class PaymentService {
                    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
                }
                """
        ));
    }

    @Test
    void doesNotModifyCodeWithoutLog4j() {
        rewriteRun(java(
                """
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class InventoryService {
                    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

                    void checkStock() {
                        log.debug("Checking stock");
                    }
                }
                """
        ));
    }

    // -------------------------------------------------------------------------
    // Maven: dependency removal
    // -------------------------------------------------------------------------

    @Test
    void removesLog4jDependencyFromPom() {
        rewriteRun(pomXml(
                """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>log4j</groupId>
                            <artifactId>log4j</artifactId>
                            <version>1.2.17</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                </project>
                """
        ));
    }

    @Test
    void removesLog4jFromDependencyManagement() {
        rewriteRun(pomXml(
                """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>log4j</groupId>
                                <artifactId>log4j</artifactId>
                                <version>1.2.17</version>
                            </dependency>
                            <dependency>
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-core</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """,
                """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-core</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """
        ));
    }

    @Test
    void fixesSlf4jErrorObjectThrowableCall() {
        // LOGGER.error(e, e) is a Log4j 1.x calling convention (error(Object, Throwable)).
        // SLF4J only accepts String as the first argument, so this call does not compile with SLF4J.
        // TypeValidation is disabled for the before-source because it deliberately contains
        // an invalid method call that the recipe is meant to fix.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;

                        class ConfigLoader {
                            private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

                            void load() {
                                try {
                                    riskyOperation();
                                } catch (Exception e) {
                                    LOGGER.error(e, e);
                                }
                            }

                            void riskyOperation() throws Exception {}
                        }
                        """,
                        """
                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;

                        class ConfigLoader {
                            private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

                            void load() {
                                try {
                                    riskyOperation();
                                } catch (Exception e) {
                                    LOGGER.error(e.getMessage(), e);
                                }
                            }

                            void riskyOperation() throws Exception {}
                        }
                        """
                )
        );
    }

    @Test
    void fixesSlf4jErrorSingleThrowableCall() {
        // LOGGER.error(e1) is a Log4j 1.x calling convention (error(Object)).
        // SLF4J has no single-arg Throwable overload, so this call does not compile with SLF4J.
        // TypeValidation is disabled for the before-source because it deliberately contains
        // an invalid method call that the recipe is meant to fix.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;

                        class ConfigLoader {
                            private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

                            void load() {
                                try {
                                    riskyOperation();
                                } catch (Exception e1) {
                                    LOGGER.error(e1);
                                }
                            }

                            void riskyOperation() throws Exception {}
                        }
                        """,
                        """
                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;

                        class ConfigLoader {
                            private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

                            void load() {
                                try {
                                    riskyOperation();
                                } catch (Exception e1) {
                                    LOGGER.error(e1.getMessage());
                                }
                            }

                            void riskyOperation() throws Exception {}
                        }
                        """
                )
        );
    }

    // Note: there is no unit test for the removal of log4j-api / log4j-core / log4j-slf4j-impl
    // because the Maven parser requires those artifacts to be in the local ~/.m2 cache, and their
    // cached versions differ between developer machines.  The correctness of those RemoveDependency
    // steps is guaranteed by the YAML recipe definition and verified by running the recipe against
    // a real project (e.g. via Moderne CLI).  The root cause analysis: Log4j1ToLog4j2 (inside
    // Log4j1ToSlf4j1) adds log4j-api, log4j-core and log4j-slf4j-impl to the POM as an
    // intermediate step, and Log4j2ToSlf4j1 does NOT remove them from Maven.
}
