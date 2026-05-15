package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.maven.Assertions.pomXml;

class RemoveEjbMavenPackagingTest implements RewriteTest {

    private static final String BARE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>email-service</artifactId>
                <version>1.0</version>
            </project>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveEjbMavenPackaging());
    }

    @DocumentExample
    @Test
    void removesEjbPackaging() {
        rewriteRun(
                pomXml(
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>email-service</artifactId>
                                    <version>1.0</version>
                                    <packaging>ejb</packaging>
                                </project>
                                """,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>email-service</artifactId>
                                    <version>1.0</version>
                                </project>
                                """
                )
        );
    }

    @Test
    void removesEjbTypeFromDependency() {
        rewriteRun(
                // Provide the email-service pom so the parser can resolve it locally
                pomXml(BARE_POM, spec -> spec.path("email-service/pom.xml")),
                pomXml(
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>web-app</artifactId>
                                    <version>1.0</version>
                                    <dependencies>
                                        <dependency>
                                            <groupId>com.example</groupId>
                                            <artifactId>email-service</artifactId>
                                            <version>1.0</version>
                                            <type>ejb</type>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>web-app</artifactId>
                                    <version>1.0</version>
                                    <dependencies>
                                        <dependency>
                                            <groupId>com.example</groupId>
                                            <artifactId>email-service</artifactId>
                                            <version>1.0</version>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """,
                        spec -> spec.path("web-app/pom.xml")
                )
        );
    }

    @Test
    void removesEjbTypeFromDependencyManagement() {
        rewriteRun(
                pomXml(BARE_POM, spec -> spec.path("email-service/pom.xml")),
                pomXml(
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>parent</artifactId>
                                    <version>1.0</version>
                                    <dependencyManagement>
                                        <dependencies>
                                            <dependency>
                                                <groupId>com.example</groupId>
                                                <artifactId>email-service</artifactId>
                                                <version>1.0</version>
                                                <type>ejb</type>
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
                                    <artifactId>parent</artifactId>
                                    <version>1.0</version>
                                    <dependencyManagement>
                                        <dependencies>
                                            <dependency>
                                                <groupId>com.example</groupId>
                                                <artifactId>email-service</artifactId>
                                                <version>1.0</version>
                                            </dependency>
                                        </dependencies>
                                    </dependencyManagement>
                                </project>
                                """,
                        spec -> spec.path("parent/pom.xml")
                )
        );
    }

    @Test
    void noChangeWhenPackagingIsJar() {
        rewriteRun(
                pomXml(
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>email-service</artifactId>
                                    <version>1.0</version>
                                    <packaging>jar</packaging>
                                </project>
                                """
                )
        );
    }

    @Test
    void noChangeWhenNoEjbType() {
        rewriteRun(
                pomXml(BARE_POM, spec -> spec.path("email-service/pom.xml")),
                pomXml(
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <project>
                                    <modelVersion>4.0.0</modelVersion>
                                    <groupId>com.example</groupId>
                                    <artifactId>web-app</artifactId>
                                    <version>1.0</version>
                                    <dependencies>
                                        <dependency>
                                            <groupId>com.example</groupId>
                                            <artifactId>email-service</artifactId>
                                            <version>1.0</version>
                                        </dependency>
                                    </dependencies>
                                </project>
                                """,
                        spec -> spec.path("web-app/pom.xml")
                )
        );
    }
}
