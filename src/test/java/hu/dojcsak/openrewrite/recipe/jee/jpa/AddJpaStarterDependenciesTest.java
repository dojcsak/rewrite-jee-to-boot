package hu.dojcsak.openrewrite.recipe.jee.jpa;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class AddJpaStarterDependenciesTest implements RewriteTest {

    private static final String ENTITY_SOURCE = """
            import javax.persistence.Entity;
            import javax.persistence.Id;

            @Entity
            class Order {
                @Id
                private Long id;
            }
            """;

    private static final String APPLICATION_SOURCE = """
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            class Application {
            }
            """;

    // Local reactor stand-ins for spring-boot-starter-data-jpa / h2 so that already-present
    // dependencies resolve without hitting the network for real Maven Central coordinates.
    private static final String LOCAL_STARTER_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-data-jpa</artifactId>
                <version>1.0</version>
            </project>
            """;

    private static final String LOCAL_H2_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.h2database</groupId>
                <artifactId>h2</artifactId>
                <version>1.0</version>
            </project>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddJpaStarterDependencies())
          .parser(JavaParser.fromJavaVersion()
            .classpath("javax.persistence-api", "spring-boot-autoconfigure"));
    }

    @DocumentExample
    @Test
    void singleModuleAddsStarterAndH2ToTheSameModule() {
        // The @SpringBootApplication class lives in the same module as the JPA usage,
        // so it trivially "depends on" the JPA module — both dependencies land there.
        rewriteRun(
          java(ENTITY_SOURCE, spec -> spec.path("src/main/java/Order.java")),
          java(APPLICATION_SOURCE, spec -> spec.path("src/main/java/Application.java")),
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
                                <artifactId>spring-boot-starter-data-jpa</artifactId>
                            </dependency>
                            <dependency>
                                <groupId>com.h2database</groupId>
                                <artifactId>h2</artifactId>
                                <scope>runtime</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("pom.xml")
          )
        );
    }

    @Test
    void multiModuleAddsH2OnlyToTheDependentSpringBootApplicationModule() {
        rewriteRun(
          // persistence module: JPA usage, no @SpringBootApplication → gets the starter, not H2
          java(ENTITY_SOURCE, spec -> spec.path("persistence/src/main/java/Order.java")),
          pomXml(
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>persistence</artifactId>
                        <version>1.0</version>
                    </project>
                    """,
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>persistence</artifactId>
                        <version>1.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-data-jpa</artifactId>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("persistence/pom.xml")
          ),
          // app module: @SpringBootApplication, depends on persistence → gets H2, not the starter
          java(APPLICATION_SOURCE, spec -> spec.path("app/src/main/java/Application.java")),
          pomXml(
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>com.example</groupId>
                                <artifactId>persistence</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
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
                                <groupId>com.example</groupId>
                                <artifactId>persistence</artifactId>
                                <version>1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>com.h2database</groupId>
                                <artifactId>h2</artifactId>
                                <scope>runtime</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void fallsBackToJpaModuleWhenAppModuleDoesNotDependOnIt() {
        rewriteRun(
          // persistence module: JPA usage → gets starter AND (via fallback) H2
          java(ENTITY_SOURCE, spec -> spec.path("persistence/src/main/java/Order.java")),
          pomXml(
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>persistence</artifactId>
                        <version>1.0</version>
                    </project>
                    """,
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>persistence</artifactId>
                        <version>1.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-data-jpa</artifactId>
                            </dependency>
                            <dependency>
                                <groupId>com.h2database</groupId>
                                <artifactId>h2</artifactId>
                                <scope>runtime</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("persistence/pom.xml")
          ),
          // app module: @SpringBootApplication, but does NOT depend on persistence → unchanged
          java(APPLICATION_SOURCE, spec -> spec.path("app/src/main/java/Application.java")),
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
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void fallsBackToJpaModuleWhenNoSpringBootApplicationIsFound() {
        rewriteRun(
          java(ENTITY_SOURCE, spec -> spec.path("persistence/src/main/java/Order.java")),
          pomXml(
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>persistence</artifactId>
                        <version>1.0</version>
                    </project>
                    """,
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>persistence</artifactId>
                        <version>1.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-data-jpa</artifactId>
                            </dependency>
                            <dependency>
                                <groupId>com.h2database</groupId>
                                <artifactId>h2</artifactId>
                                <scope>runtime</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("persistence/pom.xml")
          )
        );
    }

    @Test
    void doesNotAddAnythingWhenNoJpaUsage() {
        rewriteRun(
          java(APPLICATION_SOURCE, spec -> spec.path("src/main/java/Application.java")),
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
            spec -> spec.path("pom.xml")
          )
        );
    }

    @Test
    void isIdempotentWhenDependenciesAlreadyPresent() {
        rewriteRun(
          pomXml(LOCAL_STARTER_POM, spec -> spec.path("spring-boot-starter-data-jpa/pom.xml")),
          pomXml(LOCAL_H2_POM, spec -> spec.path("h2/pom.xml")),
          java(ENTITY_SOURCE, spec -> spec.path("persistence/src/main/java/Order.java")),
          pomXml(
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>persistence</artifactId>
                        <version>1.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-data-jpa</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("persistence/pom.xml")
          ),
          java(APPLICATION_SOURCE, spec -> spec.path("app/src/main/java/Application.java")),
          pomXml(
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>com.example</groupId>
                                <artifactId>persistence</artifactId>
                                <version>1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>com.h2database</groupId>
                                <artifactId>h2</artifactId>
                                <version>1.0</version>
                                <scope>runtime</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }
}
