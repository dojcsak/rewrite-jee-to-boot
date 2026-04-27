package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateStatelessSessionBeansTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateStatelessSessionBeans())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("javax.ejb-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void replacesStatelessWithService() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Stateless;
                                
                                @Stateless
                                class OrderService {
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;
                                
                                @Service
                                class OrderService {
                                }
                                """
                )
        );
    }

    @Test
    void preservesNameAttribute() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Stateless;
                                
                                @Stateless(name = "orderSvc")
                                class OrderService {
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;
                                
                                @Service("orderSvc")
                                class OrderService {
                                }
                                """
                )
        );
    }

    @Test
    void replacesSingletonAndRemovesStartup() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Singleton;
                                import javax.ejb.Startup;
                                
                                @Singleton
                                @Startup
                                class CacheService {
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;
                                
                                @Service
                                class CacheService {
                                }
                                """
                )
        );
    }

    @Test
    void removesLocalAnnotationFromInterface() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;
                                
                                @Local
                                interface OrderServiceLocal {
                                    void placeOrder();
                                }
                                """,
                        """
                                interface OrderServiceLocal {
                                    void placeOrder();
                                }
                                """
                )
        );
    }

    @Test
    void removesLocalBeanAnnotation() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.LocalBean;
                                import javax.ejb.Stateless;
                                
                                @Stateless
                                @LocalBean
                                class OrderService {
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;
                                
                                @Service
                                class OrderService {
                                }
                                """
                )
        );
    }

    @Test
    void skipsRemoteBeanWithAnnotationOnClass() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Remote;
                                import javax.ejb.Stateless;
                                
                                interface OrderServiceRemote {}
                                
                                @Stateless
                                @Remote(OrderServiceRemote.class)
                                class OrderService implements OrderServiceRemote {
                                }
                                """,
                        """
                                import javax.ejb.Remote;
                                import javax.ejb.Stateless;
                                
                                interface OrderServiceRemote {}
                                
                                /*~~(Skipped: bean implements @Remote interface — manual migration to Spring required)~~>*/@Stateless
                                @Remote(OrderServiceRemote.class)
                                class OrderService implements OrderServiceRemote {
                                }
                                """
                )
        );
    }
}
