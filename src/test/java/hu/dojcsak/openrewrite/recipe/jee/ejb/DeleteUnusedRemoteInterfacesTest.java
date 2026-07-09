package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DeleteUnusedRemoteInterfacesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new DeleteUnusedRemoteInterfaces());
    }

    @Test
    void deletesUnusedRemoteInterface() {
        rewriteRun(
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """,
                        (String) null
                )
        );
    }

    @Test
    void deletesInterfaceAndStripsImplementsClause() {
        rewriteRun(
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """,
                        (String) null
                ),
                java(
                        """
                        class OrderServiceBean implements OrderServiceRemote {
                            public void placeOrder(String item) {}
                        }
                        """,
                        """
                        class OrderServiceBean {
                            public void placeOrder(String item) {}
                        }
                        """
                )
        );
    }

    @Test
    void stripsOnlyTargetInterfaceFromMultipleImplements() {
        rewriteRun(
                java("interface OrderServiceLocal { void placeOrder(String item); }"),
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """,
                        (String) null
                ),
                java(
                        """
                        class OrderServiceBean implements OrderServiceLocal, OrderServiceRemote {
                            public void placeOrder(String item) {}
                        }
                        """,
                        """
                        class OrderServiceBean implements OrderServiceLocal {
                            public void placeOrder(String item) {}
                        }
                        """
                )
        );
    }

    @Test
    void doesNotDeleteInterfaceStillUsedAsType() {
        rewriteRun(
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """
                ),
                java(
                        """
                        class OrderServiceLookup {
                            OrderServiceRemote lookup() { return null; }
                        }
                        """
                )
        );
    }

    @Test
    void doesNotDeleteInterfaceReferencedInStringLiteral() {
        rewriteRun(
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """
                ),
                java(
                        """
                        class OrderServiceLookup {
                            String jndiName = "OrderServiceBean#com.example.OrderServiceRemote";
                        }
                        """
                )
        );
    }

    @Test
    void doesNotTouchInterfaceWithoutTodoMarker() {
        rewriteRun(
                java(
                        """
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """
                )
        );
    }

    @Test
    void doesNotDeleteMarkedClass() {
        rewriteRun(
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        class OrderServiceBean {
                            void placeOrder(String item) {}
                        }
                        """
                )
        );
    }

    @Test
    void stripsExtendsWhenBaseInterfaceIsDeletedButSubInterfaceIsKept() {
        rewriteRun(
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface BaseRemote {
                            void base();
                        }
                        """,
                        (String) null
                ),
                java(
                        """
                        interface SubRemote extends BaseRemote {
                            void sub();
                        }
                        """,
                        """
                        interface SubRemote {
                            void sub();
                        }
                        """
                )
        );
    }

    @Test
    void handlesMultipleIndependentCandidatesInSameRun() {
        rewriteRun(
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface FooRemote {
                            void foo();
                        }
                        """,
                        (String) null
                ),
                java(
                        """
                        class FooBean implements FooRemote {
                            public void foo() {}
                        }
                        """,
                        """
                        class FooBean {
                            public void foo() {}
                        }
                        """
                ),
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface BarRemote {
                            void bar();
                        }
                        """
                ),
                java(
                        """
                        class BarLookup {
                            BarRemote lookup() { return null; }
                        }
                        """
                )
        );
    }

    @Test
    void loadsFromYamlRecipeName() {
        rewriteRun(
                spec -> spec.recipe(
                        Environment.builder()
                                .scanRuntimeClasspath("hu.dojcsak")
                                .build()
                                .activateRecipes("hu.dojcsak.openrewrite.recipe.DeleteUnusedRemoteInterfaces")),
                java(
                        """
                        // TODO: @Remote removed - expose as REST API (e.g. @RestController)
                        interface Foo {}
                        """,
                        (String) null
                )
        );
    }
}
