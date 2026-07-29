package hu.dojcsak.openrewrite.recipe.spring;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

// DataTable rows are intentionally not asserted here — the exclusion reason each test verifies is
// already observable (and load-bearing) via the inline TODO comment produced by the same
// evaluate(...) decision, so the source-level assertions below already exercise that logic.
class ConvertFieldInjectionToLombokConstructorInjectionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConvertFieldInjectionToLombokConstructorInjection())
          .parser(JavaParser.fromJavaVersion()
            .classpath("lombok", "spring-beans", "spring-context",
              "jakarta.jws-api", "jakarta.xml.ws-api", "cxf-rt-frontend-jaxws", "cxf-core",
              "javax.jws-api", "jaxws-api"));
    }

    @DocumentExample
    @Test
    void basicFieldInjectionIsConverted() {
        rewriteRun(
          java("interface PaymentService {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class OrderService {
                  @Autowired
                  private PaymentService paymentService;
              }
              """,
            """
              import lombok.RequiredArgsConstructor;
              import org.springframework.stereotype.Service;

              @RequiredArgsConstructor
              @Service
              class OrderService {
                  private final PaymentService paymentService;
              }
              """
          )
        );
    }

    // Models the CTM GetTaskBean / CxfEndpointConfig pair: the @WebService bean is registered via
    // `new EndpointImpl(bus, impl)` where `impl` is a Spring-injected @Bean method parameter, not a
    // direct `new GetTaskBean()` call — proven instance-based registration, so the pre-existing
    // @NoArgsConstructor is safely replaced rather than treated as a reason to exclude.
    @Test
    void noArgsConstructorIsReplacedWhenRegistrationIsInstanceBased() {
        rewriteRun(
          java("interface GetTaskWSInterface {}"),
          java("interface TaskRepository {}"),
          java(
            """
              import jakarta.jws.WebService;
              import lombok.NoArgsConstructor;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @NoArgsConstructor
              @Service
              @WebService
              class GetTaskBean implements GetTaskWSInterface {
                  @Autowired
                  private TaskRepository taskRepository;
              }
              """,
            """
              import jakarta.jws.WebService;
              import lombok.RequiredArgsConstructor;
              import org.springframework.stereotype.Service;

              @RequiredArgsConstructor
              @Service
              @WebService
              class GetTaskBean implements GetTaskWSInterface {
                  private final TaskRepository taskRepository;
              }
              """
          ),
          java(
            """
              import jakarta.xml.ws.Endpoint;
              import org.apache.cxf.Bus;
              import org.apache.cxf.jaxws.EndpointImpl;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              @Configuration
              class CxfEndpointConfig {
                  @Bean
                  public Endpoint getTaskEndpoint(Bus bus, GetTaskBean impl) {
                      EndpointImpl ep = new EndpointImpl(bus, impl);
                      ep.publish("/GetTask");
                      return ep;
                  }
              }
              """
          )
        );
    }

    @Test
    void excludedWhenAncestorHasOwnAutowiredField() {
        rewriteRun(
          java("interface AuditLogger {}"),
          java("interface PaymentService {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;

              abstract class Base {
                  @Autowired
                  private AuditLogger auditLogger;
              }
              """
          ),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class OrderService extends Base {
                  @Autowired
                  private PaymentService paymentService;
              }
              """,
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              // TODO: Field injection to constructor injection conversion skipped (A): Ancestor with its own @Autowired/uninitialized final field: Base

              @Service
              class OrderService extends Base {
                  @Autowired
                  private PaymentService paymentService;
              }
              """
          )
        );
    }

    @Test
    void excludedWhenAncestorIsUnresolvedExternalType() {
        rewriteRun(
          spec -> spec.typeValidationOptions(TypeValidation.none()),
          java("interface PaymentService {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class OrderService extends com.external.UnknownBase {
                  @Autowired
                  private PaymentService paymentService;
              }
              """,
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              // TODO: Field injection to constructor injection conversion skipped (A): Unresolved/external ancestor: com.external.UnknownBase

              @Service
              class OrderService extends com.external.UnknownBase {
                  @Autowired
                  private PaymentService paymentService;
              }
              """
          )
        );
    }

    @Test
    void excludedWhenClassBasedCxfRegistrationFound() {
        rewriteRun(
          // AbstractServiceFactoryBean#setServiceClass isn't fully type-attributable from the
          // trimmed CXF test classpath used here (real Moderne/mod runs against a fully-resolved
          // target repo classpath wouldn't hit this) — the exclusion behavior itself is what's
          // under test, not full type validation of the CXF API surface.
          spec -> spec.typeValidationOptions(TypeValidation.none()),
          java("interface PricingWSInterface {}"),
          java("interface PricingRepository {}"),
          java(
            """
              import jakarta.jws.WebService;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              @WebService
              class PricingBean implements PricingWSInterface {
                  @Autowired
                  private PricingRepository pricingRepository;
              }
              """,
            """
              import jakarta.jws.WebService;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              // TODO: Field injection to constructor injection conversion skipped (B): Class-based JAX-WS/CXF registration found

              @Service
              @WebService
              class PricingBean implements PricingWSInterface {
                  @Autowired
                  private PricingRepository pricingRepository;
              }
              """
          ),
          java(
            """
              import org.apache.cxf.jaxws.JaxWsServerFactoryBean;

              class PricingEndpointRegistrar {
                  void register() {
                      JaxWsServerFactoryBean factory = new JaxWsServerFactoryBean();
                      factory.setServiceClass(PricingBean.class);
                      factory.create();
                  }
              }
              """
          )
        );
    }

    // (b) NOT excluded when registration is proven instance-based: covered by
    // noArgsConstructorIsReplacedWhenRegistrationIsInstanceBased() above.

    @Test
    void convertedWhenNoRegistrationEvidenceFoundEitherWay() {
        rewriteRun(
          java("interface ReportingWSInterface {}"),
          java("interface ReportingRepository {}"),
          java(
            """
              import jakarta.jws.WebService;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              @WebService
              class ReportingBean implements ReportingWSInterface {
                  @Autowired
                  private ReportingRepository reportingRepository;
              }
              """,
            """
              import jakarta.jws.WebService;
              import lombok.RequiredArgsConstructor;
              import org.springframework.stereotype.Service;

              @RequiredArgsConstructor
              @Service
              @WebService
              class ReportingBean implements ReportingWSInterface {
                  private final ReportingRepository reportingRepository;
              }
              """
          )
        );
    }

    @Test
    void excludedWhenDirectInstantiationFoundElsewhere() {
        rewriteRun(
          java("interface NotificationSender {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class NotificationService {
                  @Autowired
                  private NotificationSender notificationSender;
              }
              """,
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              // TODO: Field injection to constructor injection conversion skipped (C): Direct `new NotificationService(...)` call site(s): LegacyBootstrap.java

              @Service
              class NotificationService {
                  @Autowired
                  private NotificationSender notificationSender;
              }
              """
          ),
          java(
            """
              class LegacyBootstrap {
                  void init() {
                      NotificationService service = new NotificationService();
                      service.toString();
                  }
              }
              """
          )
        );
    }

    @Test
    void excludedWhenExistingConstructorHasParameters() {
        rewriteRun(
          java("interface TaxRateProvider {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class PricingCalculator {
                  @Autowired
                  private TaxRateProvider taxRateProvider;

                  public PricingCalculator(String mode) {
                      System.out.println(mode);
                  }
              }
              """,
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              // TODO: Field injection to constructor injection conversion skipped (D): Existing constructor(s) do not match the final-field set

              @Service
              class PricingCalculator {
                  @Autowired
                  private TaxRateProvider taxRateProvider;

                  public PricingCalculator(String mode) {
                      System.out.println(mode);
                  }
              }
              """
          )
        );
    }

    @Test
    void onlyAutowiredFieldsAreFinalizedAmongMixedFields() {
        rewriteRun(
          java("interface ReminderRepository {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class ReminderService {
                  private static final String DEFAULT_MESSAGE = "Reminder";

                  @Autowired
                  private ReminderRepository reminderRepository;

                  private String lastMessage;
              }
              """,
            """
              import lombok.RequiredArgsConstructor;
              import org.springframework.stereotype.Service;

              @RequiredArgsConstructor
              @Service
              class ReminderService {
                  private static final String DEFAULT_MESSAGE = "Reminder";

                  private final ReminderRepository reminderRepository;

                  private String lastMessage;
              }
              """
          )
        );
    }

    @Test
    void noAutowiredFieldOrNoStereotypeIsLeftUnchanged() {
        rewriteRun(
          java("interface SomeDependency {}"),
          java(
            """
              import org.springframework.stereotype.Service;

              @Service
              class PlainService {
                  private String name;
              }
              """
          ),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;

              class PlainAutowiredHolder {
                  @Autowired
                  private SomeDependency someDependency;
              }
              """
          )
        );
    }

    // RewriteTest re-applies the recipe to its own output by default and fails if that produces
    // further changes, so every test above already exercises idempotency for the CONVERTED path.
    // This test makes that guarantee explicit for the EXCLUDED/TODO path specifically, since the
    // TODO-comment idempotency check (matching on exact prior text) is its own piece of logic.
    @Test
    void excludedClassTodoCommentIsIdempotent() {
        rewriteRun(
          java("interface AuditLogger {}"),
          java("interface PaymentService {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;

              abstract class Base {
                  @Autowired
                  private AuditLogger auditLogger;
              }
              """
          ),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class OrderService extends Base {
                  @Autowired
                  private PaymentService paymentService;
              }
              """,
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              // TODO: Field injection to constructor injection conversion skipped (A): Ancestor with its own @Autowired/uninitialized final field: Base

              @Service
              class OrderService extends Base {
                  @Autowired
                  private PaymentService paymentService;
              }
              """
          )
        );
    }

    // Bonus coverage beyond the plan's 12 cases: the CTM UserActivityCheckServiceImpl pattern —
    // an explicit no-arg constructor that only calls super() is behaviorally identical to Lombok's
    // implicit super() call, so it can be safely dropped in favor of the generated constructor
    // (keeping it would give the class two constructors, and Spring would default to the no-arg one,
    // leaving the @Autowired field permanently unset).
    @Test
    void trivialNoArgConstructorIsRemoved() {
        rewriteRun(
          java("interface AuditLogger {}"),
          java("abstract class ServiceBase {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class AuditService extends ServiceBase {
                  @Autowired
                  private AuditLogger auditLogger;

                  public AuditService() {
                      super();
                  }
              }
              """,
            """
              import lombok.RequiredArgsConstructor;
              import org.springframework.stereotype.Service;

              @RequiredArgsConstructor
              @Service
              class AuditService extends ServiceBase {
                  private final AuditLogger auditLogger;
              }
              """
          )
        );
    }

    // Bonus coverage beyond the plan's 12 cases: package-private (no access modifier) fields need
    // the same `final` insertion as private/protected ones, exercising the "modifiers list has no
    // existing entries" branch of finalizeField(...).
    @Test
    void packagePrivateFieldIsFinalized() {
        rewriteRun(
          java("interface AuditLogger {}"),
          java(
            """
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Service;

              @Service
              class AuditService {
                  @Autowired
                  AuditLogger auditLogger;
              }
              """,
            """
              import lombok.RequiredArgsConstructor;
              import org.springframework.stereotype.Service;

              @RequiredArgsConstructor
              @Service
              class AuditService {
                  final AuditLogger auditLogger;
              }
              """
          )
        );
    }
}
