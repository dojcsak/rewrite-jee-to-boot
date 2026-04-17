package hu.dojcsak.openrewrite.recipe;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveFieldByTypeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveFieldByType("javax.ejb.SessionContext"))
                .parser(JavaParser.fromJavaVersion()
                        .classpath("javax.ejb-api", "javax.annotation-api"));
    }

    // -------------------------------------------------------------------------
    // Field not in use → removed
    // -------------------------------------------------------------------------

    @Test
    void removesUnusedFieldWithAnnotationAndComment() {
        rewriteRun(
                java(
                        """
                        import javax.annotation.Resource;
                        import javax.ejb.SessionContext;

                        class MyBean {

                            // ------ Session Context Injection ------

                            @Resource
                            protected SessionContext context;

                            void doWork() {}
                        }
                        """,
                        """
                        class MyBean {

                            void doWork() {}
                        }
                        """
                )
        );
    }

    @Test
    void removesUnusedFieldWithoutAnnotation() {
        rewriteRun(
                java(
                        """
                        import javax.ejb.SessionContext;

                        class MyBean {
                            SessionContext ctx;
                        }
                        """,
                        """
                        class MyBean {
                        }
                        """
                )
        );
    }

    @Test
    void removesOnlyMatchingUnusedField() {
        rewriteRun(
                java(
                        """
                        import javax.annotation.Resource;
                        import javax.ejb.SessionContext;

                        class MyBean {
                            @Resource
                            protected SessionContext context;

                            private String name;
                        }
                        """,
                        """
                        class MyBean {

                            private String name;
                        }
                        """
                )
        );
    }

    @Test
    void removesAllUnusedFieldsOfMatchingType() {
        rewriteRun(
                java(
                        """
                        import javax.ejb.SessionContext;

                        class MyBean {
                            SessionContext ctx1;
                            SessionContext ctx2;
                            private int count;
                        }
                        """,
                        """
                        class MyBean {
                            private int count;
                        }
                        """
                )
        );
    }

    // -------------------------------------------------------------------------
    // Field in use → TODO comment added, field kept
    // -------------------------------------------------------------------------

    @Test
    void addsTodoWhenFieldIsUsed() {
        rewriteRun(
                java(
                        """
                        import javax.annotation.Resource;
                        import javax.ejb.SessionContext;

                        class MyBean {
                            @Resource
                            protected SessionContext context;

                            void doWork() {
                                context.getCallerPrincipal();
                            }
                        }
                        """,
                        """
                        import javax.annotation.Resource;
                        import javax.ejb.SessionContext;

                        class MyBean {
                            // TODO: Remove field 'SessionContext' - still in use. Eliminate all usages first, then delete this field.
                            @Resource
                            protected SessionContext context;

                            void doWork() {
                                context.getCallerPrincipal();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void addsTodoWhenFieldIsUsedViaThis() {
        rewriteRun(
                java(
                        """
                        import javax.ejb.SessionContext;

                        class MyBean {
                            SessionContext context;

                            Object principal() {
                                return this.context.getCallerPrincipal();
                            }
                        }
                        """,
                        """
                        import javax.ejb.SessionContext;

                        class MyBean {
                            // TODO: Remove field 'SessionContext' - still in use. Eliminate all usages first, then delete this field.
                            SessionContext context;

                            Object principal() {
                                return this.context.getCallerPrincipal();
                            }
                        }
                        """
                )
        );
    }

    // -------------------------------------------------------------------------
    // No change when type does not match
    // -------------------------------------------------------------------------

    @Test
    void noChangeWhenTypeDoesNotMatch() {
        rewriteRun(
                java(
                        """
                        class MyBean {
                            private String name;
                        }
                        """
                )
        );
    }

    // -------------------------------------------------------------------------
    // Import of @Resource kept when still used elsewhere
    // -------------------------------------------------------------------------

    @Test
    void keepsResourceImportWhenUsedOnOtherField() {
        rewriteRun(
                java(
                        """
                        import javax.annotation.Resource;
                        import javax.ejb.SessionContext;

                        class MyBean {
                            @Resource
                            protected SessionContext context;

                            @Resource
                            private javax.sql.DataSource dataSource;
                        }
                        """,
                        """
                        import javax.annotation.Resource;

                        class MyBean {

                            @Resource
                            private javax.sql.DataSource dataSource;
                        }
                        """
                )
        );
    }
}
