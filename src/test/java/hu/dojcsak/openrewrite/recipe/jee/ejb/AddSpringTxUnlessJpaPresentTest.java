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
                        .classpath("spring-tx", "javax.persistence-api"));
    }

    @DocumentExample
    @Test
    void addsSpringTxWhenTransactionalUsedWithoutJpa() {
        rewriteRun(
                java(
                        """
                                import org.springframework.transaction.annotation.Transactional;

                                @Transactional
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
                                        <dependency>
                                            <groupId>org.springframework</groupId>
                                            <artifactId>spring-tx</artifactId>
                                            <version>5.3.39</version>
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
                                import org.springframework.transaction.annotation.Transactional;
                                import javax.persistence.Entity;

                                @Transactional
                                @Entity
                                class OrderEntity {
                                }
                                """
                ),
                pomXml(BARE_POM)
        );
    }

    @Test
    void doesNotAddSpringTxWhenTransactionalIsAbsent() {
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
}
