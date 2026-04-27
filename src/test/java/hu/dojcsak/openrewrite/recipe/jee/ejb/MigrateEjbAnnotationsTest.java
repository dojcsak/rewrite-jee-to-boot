package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateEjbAnnotationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbAnnotations())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("javax.ejb-api", "spring-beans"));
    }

    @DocumentExample
    @Test
    void replacesEjbFieldWithAutowired() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;
                                
                                class OrderService {
                                    @EJB
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                import org.springframework.beans.factory.annotation.Autowired;
                                
                                class OrderService {
                                    @Autowired
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addQualifierWhenBeanNamePresent() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;
                                
                                class OrderService {
                                    @EJB(beanName = "premiumPayment")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                import org.springframework.beans.factory.annotation.Autowired;
                                import org.springframework.beans.factory.annotation.Qualifier;
                                
                                class OrderService {
                                    @Autowired
                                    @Qualifier("premiumPayment")
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void replacesEjbSetterWithAutowired() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;
                                
                                class OrderService {
                                    private PaymentService paymentService;
                                
                                    @EJB
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                import org.springframework.beans.factory.annotation.Autowired;
                                
                                class OrderService {
                                    private PaymentService paymentService;
                                
                                    @Autowired
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenLookupPresent() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;
                                
                                class OrderService {
                                    @EJB(lookup = "app/PaymentService")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                import org.springframework.beans.factory.annotation.Autowired;
                                
                                class OrderService {
                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated @Autowired
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenLookupPresentOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;
                                
                                class OrderService {
                                    private PaymentService paymentService;
                                
                                    @EJB(lookup = "app/PaymentService")
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                import org.springframework.beans.factory.annotation.Autowired;
                                
                                class OrderService {
                                    private PaymentService paymentService;
                                
                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated @Autowired
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void noChangeWhenNoEjbAnnotation() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                class OrderService {
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }
}
