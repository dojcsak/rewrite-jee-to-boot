package hu.dojcsak.openrewrite.recipe;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateStatelessEjbTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                        .scanRuntimeClasspath("hu.dojcsak")
                        .build()
                        .activateRecipes("hu.dojcsak.openrewrite.recipe.MigrateStatelessEjb"))
                .parser(JavaParser.fromJavaVersion()
                        .classpath("javax.ejb-api", "javax.inject", "spring-context", "spring-beans", "spring-tx"));
    }

    @DocumentExample
    @Test
    void inlinesSingleImplementorLocalInterfaceAsPartOfFullMigration() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface OrderServiceLocal {
                                    void placeOrder();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                import javax.ejb.Stateless;

                                @Stateless
                                public class OrderServiceBean implements OrderServiceLocal {
                                    public void placeOrder() {}
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;
                                import org.springframework.transaction.annotation.Transactional;

                                @Service
                                @Transactional
                                public class OrderServiceBean {
                                    public void placeOrder() {}
                                }
                                """
                ),
                java(
                        """
                                public class OrderController {
                                    private OrderServiceLocal orderService;
                                }
                                """,
                        """
                                public class OrderController {
                                    private OrderServiceBean orderService;
                                }
                                """
                )
        );
    }

    @Test
    void addsSpringBootStarterEvenWhenOnlyEjbSignalLivedInsideTheDeletedInterface() {
        // Regression test: step 8's AppendDependency(onlyIfUsing: "javax.ejb.*") scan must still
        // detect the module's EJB usage even though step 2 (InlineLocalBeanInterfaces) fully
        // inlines and deletes the ONLY @Local interface in the module before step 8 runs.
        // OpenRewrite runs every ScanningRecipe's scan phase across the whole composite against
        // the cycle's starting state, before any step's edits (including deletions) are applied -
        // so this is NOT a regression, but it's worth pinning down with a test given how easy it
        // would be to assume otherwise from the recipeList's step ordering alone.
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null,
                        spec -> spec.path("app/src/main/java/FooLocal.java")
                ),
                java(
                        """
                                public class FooBean implements FooLocal {
                                    public void doWork() {}
                                }
                                """,
                        """
                                public class FooBean {
                                    public void doWork() {}
                                }
                                """,
                        spec -> spec.path("app/src/main/java/FooBean.java")
                ),
                pomXml(
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>app</artifactId>
                                    <version>1.0</version>
                                </project>
                                """,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>app</artifactId>
                                    <version>1.0</version>
                                    <dependencies>
                                        <dependency>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-starter</artifactId>
                                            <version>2.7.18</version>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """,
                        spec -> spec.path("app/pom.xml")
                )
        );
    }
}
