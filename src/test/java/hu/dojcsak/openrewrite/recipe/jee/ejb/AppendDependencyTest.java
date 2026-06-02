package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class AppendDependencyTest implements RewriteTest {

    // Local POM provided for Maven resolution (no network access needed).
    private static final String LOCAL_LIB_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>local-lib</artifactId>
                <version>1.0</version>
            </project>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion().classpath("javax.ejb-api"));
    }

    @DocumentExample
    @Test
    void appendsAtEndNotAlphabetically() {
        // spring-context (c) sorts alphabetically BEFORE spring-tx (t).
        // AppendDependency must place spring-context AFTER spring-tx (the existing dep),
        // not insert it before spring-tx as an alphabetical sorter would.
        rewriteRun(
          spec -> spec.recipe(new AppendDependency(
                  "org.springframework", "spring-context", "5.3.39", "javax.ejb.*")),
          java(
            """
                    import javax.ejb.Stateless;
                    @Stateless
                    class OrderService {}
                    """,
            spec -> spec.path("app/src/main/java/OrderService.java")
          ),
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
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-tx</artifactId>
                                <version>5.3.39</version>
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
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-tx</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                            <dependency>
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-context</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void doesNotInsertBlankLineWhenDependenciesBlockHasLeadingBlankLine() {
        // If <dependencies> contains a blank line before the first <dependency>,
        // the sibling prefix contains "\n\n<indent>". Normalize to "\n<indent>" so
        // no extra blank line appears before </dependency> in the newly added entry.
        rewriteRun(
          spec -> spec.recipe(new AppendDependency(
                  "org.springframework", "spring-context", "5.3.39", "javax.ejb.*")),
          java(
            """
                    import javax.ejb.Stateless;
                    @Stateless
                    class OrderService {}
                    """,
            spec -> spec.path("app/src/main/java/OrderService.java")
          ),
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
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-tx</artifactId>
                                <version>5.3.39</version>
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
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-tx</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                            <dependency>
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-context</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void doesNotAddWhenTypeNotUsed() {
        rewriteRun(
          spec -> spec.recipe(new AppendDependency(
                  "org.springframework", "spring-tx", "5.3.39", "javax.ejb.*")),
          pomXml(LOCAL_LIB_POM, spec -> spec.path("local-lib/pom.xml")),
          java(
            """
                    class PlainClass {}
                    """,
            spec -> spec.path("app/src/main/java/PlainClass.java")
          ),
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
                                <artifactId>local-lib</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void isIdempotentWhenDependencyAlreadyPresent() {
        rewriteRun(
          spec -> spec.recipe(new AppendDependency(
                  "org.springframework", "spring-tx", "5.3.39", "javax.ejb.*")),
          java(
            """
                    import javax.ejb.Stateless;
                    @Stateless
                    class OrderService {}
                    """,
            spec -> spec.path("app/src/main/java/OrderService.java")
          ),
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
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-tx</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void createsDependenciesSectionWhenAbsent() {
        rewriteRun(
          spec -> spec.recipe(new AppendDependency(
                  "org.springframework", "spring-tx", "5.3.39", "javax.ejb.*")),
          java(
            """
                    import javax.ejb.Stateless;
                    @Stateless
                    class OrderService {}
                    """,
            spec -> spec.path("app/src/main/java/OrderService.java")
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
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-tx</artifactId>
                                <version>5.3.39</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void omitsVersionWhenManagedByDependencyManagement() {
        // spring-tx:5.3.39 is declared in dependencyManagement → version must be omitted from <dependencies>
        rewriteRun(
          spec -> spec.recipe(new AppendDependency(
                  "org.springframework", "spring-tx", "5.3.39", "javax.ejb.*")),
          java(
            """
                    import javax.ejb.Stateless;
                    @Stateless
                    class OrderService {}
                    """,
            spec -> spec.path("app/src/main/java/OrderService.java")
          ),
          pomXml(
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
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
                        <artifactId>app</artifactId>
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
                    """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }
}
