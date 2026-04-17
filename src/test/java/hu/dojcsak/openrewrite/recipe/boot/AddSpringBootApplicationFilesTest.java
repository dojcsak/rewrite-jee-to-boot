package hu.dojcsak.openrewrite.recipe.boot;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.test.TypeValidation;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;

class AddSpringBootApplicationFilesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddSpringBootApplicationFiles());
    }

    // -------------------------------------------------------------------------
    // File generation
    // -------------------------------------------------------------------------

    @Test
    void generatesAllThreeFilesWhenNoneExist() {
        rewriteRun(
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("src/main/java/SpringBootApp.java")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("src/test/java/SpringBootAppTest.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("src/main/resources/application.properties")
                )
        );
    }

    @Test
    void placesGeneratedFilesInDetectedPackage() {
        rewriteRun(
                java(
                        """
                        package com.example.service;

                        class OrderService {}
                        """,
                        spec -> spec.path("src/main/java/com/example/service/OrderService.java")
                ),
                java(
                        null,
                        """
                        package com.example.service;

                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("src/main/java/com/example/service/SpringBootApp.java")
                ),
                java(
                        null,
                        """
                        package com.example.service;

                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("src/test/java/com/example/service/SpringBootAppTest.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("src/main/resources/application.properties")
                )
        );
    }

    @Test
    void usesLongestCommonPrefixWhenMultiplePackages() {
        rewriteRun(
                java(
                        """
                        package com.example.service;

                        class OrderService {}
                        """,
                        spec -> spec.path("src/main/java/com/example/service/OrderService.java")
                ),
                java(
                        """
                        package com.example.repository;

                        class OrderRepository {}
                        """,
                        spec -> spec.path("src/main/java/com/example/repository/OrderRepository.java")
                ),
                java(
                        null,
                        """
                        package com.example;

                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("src/main/java/com/example/SpringBootApp.java")
                ),
                java(
                        null,
                        """
                        package com.example;

                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("src/test/java/com/example/SpringBootAppTest.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("src/main/resources/application.properties")
                )
        );
    }

    // -------------------------------------------------------------------------
    // Skip when already present
    // -------------------------------------------------------------------------

    @Test
    void skipsMainClassIfSpringBootApplicationAlreadyPresent() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class MyApp {
                            public static void main(String[] args) {
                                SpringApplication.run(MyApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("src/main/java/MyApp.java")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("src/test/java/SpringBootAppTest.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("src/main/resources/application.properties")
                )
        );
    }

    @Test
    void skipsTestClassIfSpringBootTestAlreadyPresent() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class MyAppTest {
                        }
                        """,
                        spec -> spec.path("src/test/java/MyAppTest.java")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("src/main/java/SpringBootApp.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("src/main/resources/application.properties")
                )
        );
    }

    @Test
    void skipsApplicationPropertiesIfAlreadyPresent() {
        rewriteRun(
                properties(
                        "server.port=8080\n",
                        spec -> spec.path("src/main/resources/application.properties")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("src/main/java/SpringBootApp.java")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("src/test/java/SpringBootAppTest.java")
                )
        );
    }

    // -------------------------------------------------------------------------
    // Multi-module: war-in-ear exclusion
    // -------------------------------------------------------------------------

    @Test
    void multiModuleWarInEarGetsFilesOnlyInEarModule() {
        // web (war) is a dependency of ear → files generated in ear only, NOT in web
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                        </project>
                        """,
                        spec -> spec.path("web/pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                ),
                // ear module gets all three generated files
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("ear/src/main/java/SpringBootApp.java")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("ear/src/test/java/SpringBootAppTest.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("ear/src/main/resources/application.properties")
                )
                // web module gets NO generated files (it is in ear's dependencies)
        );
    }

    @Test
    void multiModuleWarNotInEarGetsFilesInBothModules() {
        // web (war) is NOT a dependency of ear → files generated in both ear and web
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                        </project>
                        """,
                        spec -> spec.path("web/pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                ),
                // ear module gets generated files
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("ear/src/main/java/SpringBootApp.java")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("ear/src/test/java/SpringBootAppTest.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("ear/src/main/resources/application.properties")
                ),
                // web module also gets generated files (not in ear's deps)
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class SpringBootApp {

                            public static void main(String[] args) {
                                SpringApplication.run(SpringBootApp.class, args);
                            }
                        }
                        """,
                        spec -> spec.path("web/src/main/java/SpringBootApp.java")
                ),
                java(
                        null,
                        """
                        // TODO: Move this class into the appropriate package.
                        import org.junit.jupiter.api.Test;
                        import org.springframework.boot.test.context.SpringBootTest;

                        @SpringBootTest
                        class SpringBootAppTest {

                            @Test
                            void contextLoads() {
                            }
                        }
                        """,
                        spec -> spec.path("web/src/test/java/SpringBootAppTest.java")
                ),
                properties(
                        null,
                        "",
                        spec -> spec.path("web/src/main/resources/application.properties")
                )
        );
    }

    // -------------------------------------------------------------------------
    // detectPackage unit tests
    // -------------------------------------------------------------------------

    @Test
    void detectPackageReturnsEmptyForNoPackages() {
        assertThat(AddSpringBootApplicationFiles.detectPackage(Set.of()))
                .isEqualTo("");
    }

    @Test
    void detectPackageReturnsSinglePackageAsIs() {
        assertThat(AddSpringBootApplicationFiles.detectPackage(Set.of("com.example.service")))
                .isEqualTo("com.example.service");
    }

    @Test
    void detectPackageReturnsCommonPrefix() {
        Set<String> packages = new LinkedHashSet<>(
                Arrays.asList("com.example.service", "com.example.repository"));
        assertThat(AddSpringBootApplicationFiles.detectPackage(packages))
                .isEqualTo("com.example");
    }

    @Test
    void detectPackageReturnsEmptyWhenNoCommonSegments() {
        Set<String> packages = new LinkedHashSet<>(Arrays.asList("com.foo", "org.bar"));
        assertThat(AddSpringBootApplicationFiles.detectPackage(packages))
                .isEqualTo("");
    }

    // -------------------------------------------------------------------------
    // generate() unit tests
    // -------------------------------------------------------------------------

    @Test
    void generatedApplicationPropertiesContainsEmptyLine() {
        AddSpringBootApplicationFiles recipe = new AddSpringBootApplicationFiles();
        InMemoryExecutionContext ctx = new InMemoryExecutionContext();
        AddSpringBootApplicationFiles.Accumulator acc = recipe.getInitialValue(ctx);

        Collection<? extends SourceFile> generated = recipe.generate(acc, ctx);

        SourceFile props = generated.stream()
                .filter(f -> AddSpringBootApplicationFiles.hasFileName(f.getSourcePath(), "application.properties"))
                .findFirst()
                .orElseThrow();

        assertThat(props.printAll()).isNotEmpty().isBlank();
    }
}
