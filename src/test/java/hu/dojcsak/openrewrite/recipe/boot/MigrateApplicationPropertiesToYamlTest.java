package hu.dojcsak.openrewrite.recipe.boot;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

class MigrateApplicationPropertiesToYamlTest implements RewriteTest {

    private static final String APP_PROPS_PATH = "src/main/resources/application.properties";
    private static final String APP_YAML_PATH = "src/main/resources/application.yaml";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateApplicationPropertiesToYaml());
    }

    @Test
    void convertsFlatKeysAndDeletesPropertiesFile() {
        rewriteRun(
                properties(
                        """
                        server.port=8080
                        """,
                        (String) null,
                        spec -> spec.path(APP_PROPS_PATH)
                ),
                yaml(
                        null,
                        """
                        server:
                          port: 8080
                        """,
                        spec -> spec.path(APP_YAML_PATH)
                )
        );
    }

    @Test
    void convertsProfileSpecificPropertiesFile() {
        rewriteRun(
                properties(
                        """
                        server.port=9090
                        """,
                        (String) null,
                        spec -> spec.path("src/main/resources/application-dev.properties")
                ),
                yaml(
                        null,
                        """
                        server:
                          port: 9090
                        """,
                        spec -> spec.path("src/main/resources/application-dev.yaml")
                )
        );
    }

    @Test
    void noOpWhenNoApplicationPropertiesFile() {
        rewriteRun(
                properties(
                        "some.other.key=value\n",
                        spec -> spec.path("src/main/resources/other.properties")
                )
        );
    }

    @Test
    void skipsConversionWhenTargetYamlAlreadyExists() {
        rewriteRun(
                properties(
                        "server.port=8080\n",
                        spec -> spec.path(APP_PROPS_PATH)
                ),
                yaml(
                        """
                        server:
                          port: 9999
                        """,
                        spec -> spec.path(APP_YAML_PATH)
                )
        );
    }

    @Test
    void nestsSharedPrefixIntoSingleMapping() {
        rewriteRun(
                properties(
                        """
                        spring.datasource.url=jdbc:h2:mem:testdb
                        spring.datasource.username=sa
                        """,
                        (String) null,
                        spec -> spec.path(APP_PROPS_PATH)
                ),
                yaml(
                        null,
                        """
                        spring:
                          datasource:
                            url: jdbc:h2:mem:testdb
                            username: sa
                        """,
                        spec -> spec.path(APP_YAML_PATH)
                )
        );
    }

    @Test
    void carriesOverSingleCommentLineAboveProperty() {
        rewriteRun(
                properties(
                        """
                        # Server port
                        server.port=8080
                        """,
                        (String) null,
                        spec -> spec.path(APP_PROPS_PATH)
                ),
                yaml(
                        null,
                        """
                        server:
                          # Server port
                          port: 8080
                        """,
                        spec -> spec.path(APP_YAML_PATH)
                )
        );
    }

    @Test
    void carriesOverMultipleConsecutiveCommentLines() {
        rewriteRun(
                properties(
                        """
                        # First line
                        # Second line
                        server.port=8080
                        """,
                        (String) null,
                        spec -> spec.path(APP_PROPS_PATH)
                ),
                yaml(
                        null,
                        """
                        server:
                          # First line
                          # Second line
                          port: 8080
                        """,
                        spec -> spec.path(APP_YAML_PATH)
                )
        );
    }

    @Test
    void attachesCommentsToTheCorrectLeafWhenKeysShareAPrefix() {
        rewriteRun(
                properties(
                        """
                        # Datasource URL
                        spring.datasource.url=jdbc:h2:mem:testdb
                        # Datasource username
                        spring.datasource.username=sa
                        """,
                        (String) null,
                        spec -> spec.path(APP_PROPS_PATH)
                ),
                yaml(
                        null,
                        """
                        spring:
                          datasource:
                            # Datasource URL
                            url: jdbc:h2:mem:testdb
                            # Datasource username
                            username: sa
                        """,
                        spec -> spec.path(APP_YAML_PATH)
                )
        );
    }

    @Test
    void normalizesExclamationMarkCommentsToHash() {
        rewriteRun(
                properties(
                        """
                        ! Server port
                        server.port=8080
                        """,
                        (String) null,
                        spec -> spec.path(APP_PROPS_PATH)
                ),
                yaml(
                        null,
                        """
                        server:
                          # Server port
                          port: 8080
                        """,
                        spec -> spec.path(APP_YAML_PATH)
                )
        );
    }
}
