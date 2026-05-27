package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class AddSpringTxUnlessJpaPresentTest implements RewriteTest {

    private static final String BARE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>example</artifactId>
                <version>1.0</version>
            </project>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddSpringTxUnlessJpaPresent())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("javax.ejb-api", "javax.persistence-api"));
    }

    @Test
    void addsSpringTxForStatelessBeanWithoutJpa() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Stateless;

                                @Stateless
                                class EmailService {
                                }
                                """
                ),
                pomXml(
                        BARE_POM,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>example</artifactId>
                                    <version>1.0</version>
                                    <dependencies>
                                        <!--~~(No version provided for direct dependency org.springframework:spring-tx:compile)~~>--><dependency>
                                            <groupId>org.springframework</groupId>
                                            <artifactId>spring-tx</artifactId>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """
                )
        );
    }

    @Test
    void addsSpringTxForSingletonBeanWithoutJpa() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Singleton;

                                @Singleton
                                class CacheService {
                                }
                                """
                ),
                pomXml(
                        BARE_POM,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>example</artifactId>
                                    <version>1.0</version>
                                    <dependencies>
                                        <!--~~(No version provided for direct dependency org.springframework:spring-tx:compile)~~>--><dependency>
                                            <groupId>org.springframework</groupId>
                                            <artifactId>spring-tx</artifactId>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """
                )
        );
    }

    @Test
    void doesNotAddSpringTxWhenJpaIsPresent() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Stateless;
                                import javax.persistence.EntityManager;
                                import javax.persistence.PersistenceContext;

                                @Stateless
                                class OrderService {
                                    @PersistenceContext
                                    private EntityManager em;
                                }
                                """
                ),
                pomXml(BARE_POM)
        );
    }

    @Test
    void doesNotAddSpringTxWhenNoEjbSessionBean() {
        rewriteRun(
                java(
                        """
                                class EmailService {
                                }
                                """
                ),
                pomXml(BARE_POM)
        );
    }

    @DocumentExample
    @Test
    void omitsVersionTag() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Stateless;

                                @Stateless
                                class EmailService {
                                }
                                """
                ),
                pomXml(
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>example</artifactId>
                                    <version>1.0</version>
                                    <dependencyManagement>
                                        <dependencies>
                                            <dependency>
                                                <groupId>org.springframework</groupId>
                                                <artifactId>spring-tx</artifactId>
                                                <version>5.3.39</version>
                                            </dependency>
                                        </dependencies>
                                    </dependencyManagement>
                                </project>
                                """,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>example</artifactId>
                                    <version>1.0</version>
                                    <dependencyManagement>
                                        <dependencies>
                                            <dependency>
                                                <groupId>org.springframework</groupId>
                                                <artifactId>spring-tx</artifactId>
                                                <version>5.3.39</version>
                                            </dependency>
                                        </dependencies>
                                    </dependencyManagement>
                                    <dependencies>
                                        <dependency>
                                            <groupId>org.springframework</groupId>
                                            <artifactId>spring-tx</artifactId>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """
                )
        );
    }

    @Test
    void addsSpringTxToNonJpaModuleEvenWhenOtherModuleUsesJpa() {
        rewriteRun(
                // jpa-module: @Stateless + javax.persistence.* → JPA module, spring-tx must NOT be added directly
                java(
                        """
                                import javax.ejb.Stateless;
                                import javax.persistence.EntityManager;
                                import javax.persistence.PersistenceContext;

                                @Stateless
                                class OrderService {
                                    @PersistenceContext
                                    private EntityManager em;
                                }
                                """,
                        spec -> spec.path("jpa-module/src/main/java/OrderService.java")
                ),
                pomXml(BARE_POM, spec -> spec.path("jpa-module/pom.xml")),
                // email-module: @Stateless, no JPA → spring-tx MUST be added
                java(
                        """
                                import javax.ejb.Stateless;

                                @Stateless
                                class EmailService {
                                }
                                """,
                        spec -> spec.path("email-module/src/main/java/EmailService.java")
                ),
                pomXml(
                        BARE_POM,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>example</artifactId>
                                    <version>1.0</version>
                                    <dependencies>
                                        <!--~~(No version provided for direct dependency org.springframework:spring-tx:compile)~~>--><dependency>
                                            <groupId>org.springframework</groupId>
                                            <artifactId>spring-tx</artifactId>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """,
                        spec -> spec.path("email-module/pom.xml")
                )
        );
    }
}
