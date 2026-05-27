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
    void addQualifierWhenBeanNamePresentOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(beanName = "premiumPayment")
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                import org.springframework.beans.factory.annotation.Autowired;
                                import org.springframework.beans.factory.annotation.Qualifier;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @Autowired
                                    @Qualifier("premiumPayment")
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
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
                                class OrderService {
                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated
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
                                class OrderService {
                                    private PaymentService paymentService;
                                
                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenBeanInterfacePresentOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(beanInterface = PaymentService.class)
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                class OrderService {
                                    private PaymentService paymentService;

                                    // TODO: @EJB(beanInterface = PaymentService.class) could not be automatically migrated
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenBeanNameIsNonLiteralOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java("class BeanNames { static final String PAYMENT_IMPL = \"paymentImpl\"; }"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(beanName = BeanNames.PAYMENT_IMPL)
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                class OrderService {
                                    private PaymentService paymentService;

                                    // TODO: @EJB(beanName = BeanNames.PAYMENT_IMPL) could not be automatically migrated
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenBeanNameIsNonLiteral() {
        rewriteRun(
                java("interface PaymentService {}"),
                java("class BeanNames { static final String PAYMENT_IMPL = \"paymentImpl\"; }"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(beanName = BeanNames.PAYMENT_IMPL)
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(beanName = BeanNames.PAYMENT_IMPL) could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentForPackagePrivateFieldWithLookup() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(lookup = "app/PaymentService")
                                    PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated
                                    PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentForPackagePrivateSetterWithLookup() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    PaymentService paymentService;

                                    @EJB(lookup = "app/PaymentService")
                                    void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                class OrderService {
                                    PaymentService paymentService;

                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated
                                    void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenEjbNamePresent() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(name = "ejb/PaymentService")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(name = "ejb/PaymentService") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void noTodoCommentWhenEjbNameIsEmpty() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(name = "")
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
    void addsTodoCommentWhenEjbNamePresentOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(name = "ejb/PaymentService")
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                class OrderService {
                                    private PaymentService paymentService;

                                    // TODO: @EJB(name = "ejb/PaymentService") could not be automatically migrated
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenBeanInterfacePresent() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(beanInterface = PaymentService.class)
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(beanInterface = PaymentService.class) could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void stripsBlockCommentsFromTodoMessage() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(/* JNDI */ lookup = "app/PaymentService")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void stripsMultiLineBlockCommentsFromTodoMessage() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(/*
                                         JNDI
                                         */ lookup = "app/PaymentService")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(lookup = "app/PaymentService") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void preservesSlashStarInsideStringLiteralInTodoMessage() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(lookup = "app//* alias */PaymentService")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(lookup = "app//* alias */PaymentService") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void preservesParensInsideStringLiteralInTodoMessage() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(lookup = "app/payment (service)")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(lookup = "app/payment (service)") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenMappedNamePresent() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(mappedName = "java:global/app/PaymentService")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(mappedName = "java:global/app/PaymentService") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenMappedNamePresentOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(mappedName = "java:global/app/PaymentService")
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                class OrderService {
                                    private PaymentService paymentService;

                                    // TODO: @EJB(mappedName = "java:global/app/PaymentService") could not be automatically migrated
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void noTodoCommentWhenMappedNameIsEmpty() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(mappedName = "")
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
    void noTodoCommentWhenMappedNameIsEmptyOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(mappedName = "")
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
    void addsTodoCommentWhenMappedNameIsNonLiteral() {
        rewriteRun(
                java("interface PaymentService {}"),
                java("class JndiNames { static final String PAYMENT_SVC = \"java:global/app/PaymentService\"; }"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(mappedName = JndiNames.PAYMENT_SVC)
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(mappedName = JndiNames.PAYMENT_SVC) could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenMappedNameIsNonLiteralOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java("class JndiNames { static final String PAYMENT_SVC = \"java:global/app/PaymentService\"; }"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(mappedName = JndiNames.PAYMENT_SVC)
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                class OrderService {
                                    private PaymentService paymentService;

                                    // TODO: @EJB(mappedName = JndiNames.PAYMENT_SVC) could not be automatically migrated
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenBothBeanNameAndMappedNamePresent() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(beanName = "premiumPayment", mappedName = "java:global/app/PaymentService")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(beanName = "premiumPayment", mappedName = "java:global/app/PaymentService") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenLookupIsNonLiteral() {
        rewriteRun(
                java("interface PaymentService {}"),
                java("class JndiNames { static final String PAYMENT_SVC = \"app/PaymentService\"; }"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(lookup = JndiNames.PAYMENT_SVC)
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(lookup = JndiNames.PAYMENT_SVC) could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void noTodoCommentWhenLookupIsEmpty() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(lookup = "")
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
    void doesNotProcessConstructorAnnotatedWithEjb() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB
                                    OrderService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void addsTodoCommentWhenDescriptionPresent() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(description = "Primary payment gateway")
                                    private PaymentService paymentService;
                                }
                                """,
                        """
                                class OrderService {
                                    // TODO: @EJB(description = "Primary payment gateway") could not be automatically migrated
                                    private PaymentService paymentService;
                                }
                                """
                )
        );
    }

    @Test
    void noTodoCommentWhenDescriptionIsEmpty() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    @EJB(description = "")
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
    void addsTodoCommentWhenDescriptionPresentOnSetter() {
        rewriteRun(
                java("interface PaymentService {}"),
                java(
                        """
                                import javax.ejb.EJB;

                                class OrderService {
                                    private PaymentService paymentService;

                                    @EJB(description = "Primary payment gateway")
                                    public void setPaymentService(PaymentService paymentService) {
                                        this.paymentService = paymentService;
                                    }
                                }
                                """,
                        """
                                class OrderService {
                                    private PaymentService paymentService;

                                    // TODO: @EJB(description = "Primary payment gateway") could not be automatically migrated
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
