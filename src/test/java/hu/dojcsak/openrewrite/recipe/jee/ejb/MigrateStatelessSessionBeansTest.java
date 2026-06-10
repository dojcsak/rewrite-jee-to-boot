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

                          // TODO: @Startup removed - Spring @Service is lazy by default; add @Lazy(false) if eager initialization is required
                          @Service
                          class CacheService {
                          }
                          """
          )
        );
    }

    @Test
    void removesStartupFromStateless() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Startup;
                          import javax.ejb.Stateless;

                          @Stateless
                          @Startup
                          class OrderService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;

                          // TODO: @Startup removed - Spring @Service is lazy by default; add @Lazy(false) if eager initialization is required
                          @Service
                          class OrderService {
                          }
                          """
          )
        );
    }

    @Test
    void preservesNameAttributeOnSingleton() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Singleton;
                          
                          @Singleton(name = "cacheService")
                          class CacheService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;
                          
                          @Service("cacheService")
                          class CacheService {
                          }
                          """
          )
        );
    }

    @Test
    void preservesNameAttributeOnSingletonWithStartup() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Singleton;
                          import javax.ejb.Startup;
                          
                          @Singleton(name = "cacheService")
                          @Startup
                          class CacheService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;

                          // TODO: @Startup removed - Spring @Service is lazy by default; add @Lazy(false) if eager initialization is required
                          @Service("cacheService")
                          class CacheService {
                          }
                          """
          )
        );
    }

    @Test
    void treatsEmptyNameAsAbsentOnStateless() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless(name = "")
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
    void treatsEmptyNameAsAbsentOnSingleton() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Singleton;
                          
                          @Singleton(name = "")
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
    void removesLocalAnnotationFromInterfaceWithJavadoc() {
        // Removing @Local must not leave a blank line between the Javadoc and the interface keyword.
        rewriteRun(
          java(
                  """
                          import javax.ejb.Local;

                          /**
                           * Local interface for the session bean.
                           */
                          @Local
                          public interface OrderServiceLocal {
                              void placeOrder();
                          }
                          """,
                  """
                          /**
                           * Local interface for the session bean.
                           */
                          public interface OrderServiceLocal {
                              void placeOrder();
                          }
                          """
          )
        );
    }

    @Test
    void removesLocalAnnotationFromInterfaceWithJavadocCrlf() {
        // Same as above but with CRLF line endings (Windows files) - regression test for a bug
        // where @Local removal left a blank line between the Javadoc closing "*/" and the interface keyword.
        String NL = "\r\n";
        String input =
                "import javax.ejb.Local;" + NL +
                NL +
                "/**" + NL +
                " * Local interface for the session bean." + NL +
                " */" + NL +
                "@Local" + NL +
                "public interface OrderServiceLocal {" + NL +
                "    void placeOrder();" + NL +
                "}" + NL;
        String expected =
                "/**" + NL +
                " * Local interface for the session bean." + NL +
                " */" + NL +
                "public interface OrderServiceLocal {" + NL +
                "    void placeOrder();" + NL +
                "}" + NL;
        rewriteRun(java(input, expected));
    }

    @Test
    void removesLocalAnnotationCrlfWithPackageAndLineComments() {
        // Regression test: CRLF files with a package declaration and line comments at the top
        // left a spurious blank line between the Javadoc "*/" and the interface keyword after @Local removal.
        String NL = "\r\n";
        String input =
                "// license-header" + NL +
                "//" + NL +
                "package com.example;" + NL +
                NL +
                "import javax.ejb.Local;" + NL +
                NL +
                "/**" + NL +
                " * Local interface." + NL +
                " */" + NL +
                "@Local" + NL +
                "public interface OrderServiceLocal {" + NL +
                "}" + NL;
        String expected =
                "// license-header" + NL +
                "//" + NL +
                "package com.example;" + NL +
                NL +
                "/**" + NL +
                " * Local interface." + NL +
                " */" + NL +
                "public interface OrderServiceLocal {" + NL +
                "}" + NL;
        rewriteRun(java(input, expected));
    }

    @Test
    void removesLocalBeanAnnotationFromInterface() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.LocalBean;
                          
                          @LocalBean
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
    void removesLocalAndLocalBeanFromInterface() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Local;
                          import javax.ejb.LocalBean;
                          
                          @Local
                          @LocalBean
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
    void flagsMappedNameAttributeOnStateless() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless(mappedName = "java:global/app/OrderService")
                          class OrderService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;

                          // TODO: mappedName = "java:global/app/OrderService" could not be automatically migrated - configure the JNDI binding in Spring manually
                          @Service
                          class OrderService {
                          }
                          """
          )
        );
    }

    @Test
    void flagsMappedNameAttributeOnSingleton() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Singleton;
                          
                          @Singleton(mappedName = "java:global/app/CacheService")
                          class CacheService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;

                          // TODO: mappedName = "java:global/app/CacheService" could not be automatically migrated - configure the JNDI binding in Spring manually
                          @Service
                          class CacheService {
                          }
                          """
          )
        );
    }

    @Test
    void treatsEmptyMappedNameAsAbsent() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless(mappedName = "")
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
    void flagsCombinedAttributesAsSingleMarker() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Singleton;
                          import javax.ejb.Startup;
                          
                          @Singleton(mappedName = "java:global/app/CacheService")
                          @Startup
                          class CacheService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;

                          // TODO: mappedName = "java:global/app/CacheService" could not be automatically migrated - configure the JNDI binding in Spring manually; @Startup removed - Spring @Service is lazy by default; add @Lazy(false) if eager initialization is required
                          @Service
                          class CacheService {
                          }
                          """
          )
        );
    }

    @Test
    void flagsNonLiteralNameAttribute() {
        rewriteRun(
          java("class BeanNames { static final String ORDER_SVC = \"orderSvc\"; }"),
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless(name = BeanNames.ORDER_SVC)
                          class OrderService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;

                          // TODO: name attribute could not be automatically migrated - set the @Service bean name manually
                          @Service
                          class OrderService {
                          }
                          """
          )
        );
    }

    @Test
    void removesLocalAndLocalBeanAnnotationsTogether() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Local;
                          import javax.ejb.LocalBean;
                          import javax.ejb.Stateless;
                          
                          interface OrderServiceLocal {}
                          
                          @Stateless
                          @Local
                          @LocalBean
                          class OrderService implements OrderServiceLocal {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;
                          
                          interface OrderServiceLocal {}
                          
                          @Service
                          class OrderService implements OrderServiceLocal {
                          }
                          """
          )
        );
    }

    @Test
    void removesLocalAnnotationFromBeanClass() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Local;
                          import javax.ejb.Stateless;
                          
                          interface OrderServiceLocal {}
                          
                          @Stateless
                          @Local
                          class OrderService implements OrderServiceLocal {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;
                          
                          interface OrderServiceLocal {}
                          
                          @Service
                          class OrderService implements OrderServiceLocal {
                          }
                          """
          )
        );
    }

    @Test
    void skipsRemoteBeanImplementingMultipleInterfaces() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Remote;
                          
                          @Remote
                          interface OrderServiceRemote {
                              void placeOrder();
                          }
                          """
          ),
          java("interface OrderServiceLocal {}"),
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless
                          class OrderService implements OrderServiceLocal, OrderServiceRemote {
                          }
                          """,
                  """
                          import javax.ejb.Stateless;
                          
                          /*~~(Skipped: bean implements @Remote interface - manual migration to Spring required)~~>*/@Stateless
                          class OrderService implements OrderServiceLocal, OrderServiceRemote {
                          }
                          """
          )
        );
    }

    @Test
    void skipsRemoteBeanWhenInterfaceAnnotatedWithRemote() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Remote;
                          
                          @Remote
                          interface OrderServiceRemote {
                              void placeOrder();
                          }
                          """
          ),
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless
                          class OrderService implements OrderServiceRemote {
                          }
                          """,
                  """
                          import javax.ejb.Stateless;
                          
                          /*~~(Skipped: bean implements @Remote interface - manual migration to Spring required)~~>*/@Stateless
                          class OrderService implements OrderServiceRemote {
                          }
                          """
          )
        );
    }

    @Test
    void skipsRemoteBeanInheritingRemoteInterfaceThroughSuperclass() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Remote;
                          
                          @Remote
                          interface OrderServiceRemote {
                              void placeOrder();
                          }
                          """
          ),
          java(
                  """
                          abstract class AbstractOrderService implements OrderServiceRemote {
                          }
                          """
          ),
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless
                          class OrderService extends AbstractOrderService {
                          }
                          """,
                  """
                          import javax.ejb.Stateless;
                          
                          /*~~(Skipped: bean implements @Remote interface - manual migration to Spring required)~~>*/@Stateless
                          class OrderService extends AbstractOrderService {
                          }
                          """
          )
        );
    }

    @Test
    void skipsRemoteBeanWhenInterfaceExtendsRemoteInterface() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Remote;
                          
                          @Remote
                          interface OrderServiceBase {
                              void placeOrder();
                          }
                          """
          ),
          java(
                  """
                          interface OrderServiceRemote extends OrderServiceBase {
                          }
                          """
          ),
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless
                          class OrderService implements OrderServiceRemote {
                          }
                          """,
                  """
                          import javax.ejb.Stateless;
                          
                          /*~~(Skipped: bean implements @Remote interface - manual migration to Spring required)~~>*/@Stateless
                          class OrderService implements OrderServiceRemote {
                          }
                          """
          )
        );
    }

    @Test
    void flagsDescriptionAttributeOnStateless() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless(description = "Handles order placement")
                          class OrderService {
                          }
                          """,
                  """
                          import org.springframework.stereotype.Service;

                          // TODO: description attribute has no Spring equivalent - consider preserving it as a code comment
                          @Service
                          class OrderService {
                          }
                          """
          )
        );
    }

    @Test
    void treatsEmptyDescriptionAsAbsentOnStateless() {
        rewriteRun(
          java(
                  """
                          import javax.ejb.Stateless;
                          
                          @Stateless(description = "")
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
                          
                          /*~~(Skipped: bean implements @Remote interface - manual migration to Spring required)~~>*/@Stateless
                          @Remote(OrderServiceRemote.class)
                          class OrderService implements OrderServiceRemote {
                          }
                          """
          )
        );
    }
}
