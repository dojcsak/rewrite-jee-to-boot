package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MarkRemoteEjbsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkRemoteEjbs())
                .parser(JavaParser.fromJavaVersion().classpath("javax.ejb-api"));
    }

    @Test
    void marksRemoteInterface() {
        rewriteRun(
                java(
                        """
                        import javax.ejb.Remote;

                        @Remote
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """,
                        """
                        // TODO: @Remote removed \u2014 expose as REST API (e.g. @RestController)
                        interface OrderServiceRemote {
                            void placeOrder(String item);
                        }
                        """
                )
        );
    }

    @Test
    void marksRemoteAnnotationWithClassArgument() {
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
                        import javax.ejb.Stateless;

                        interface OrderServiceRemote {}

                        // TODO: @Remote(OrderServiceRemote.class) removed \u2014 expose as REST API (e.g. @RestController)
                        @Stateless
                        class OrderService implements OrderServiceRemote {
                        }
                        """
                )
        );
    }

    @Test
    void marksRemoteInterfaceWithJavadocAndBlankLineBefore() {
        rewriteRun(
                java(
                        """
                        import javax.ejb.Remote;

                        /**
                         * Javadoc.
                         */
                        @Remote
                        public interface FirstInterface {}

                        /**
                         * Second javadoc.
                         */
                        @Remote
                        public interface SecondInterface {
                            void doSomething();
                        }
                        """,
                        """
                        /**
                         * Javadoc.
                         */
                        // TODO: @Remote removed \u2014 expose as REST API (e.g. @RestController)
                        public interface FirstInterface {}

                        /**
                         * Second javadoc.
                         */
                        // TODO: @Remote removed \u2014 expose as REST API (e.g. @RestController)
                        public interface SecondInterface {
                            void doSomething();
                        }
                        """
                )
        );
    }

    @Test
    void marksRemoteInterfaceWithLicenseHeaderAndExtends() {
        rewriteRun(
                java("package com.example; interface BaseService {}"),
                java(
                        """
                        // license-header
                        //
                        package com.example;

                        import javax.ejb.Remote;

                        /**
                         * Javadoc.
                         */
                        @Remote
                        public interface OrderServiceRemote
                            extends BaseService
                        {
                        }
                        """,
                        """
                        // license-header
                        //
                        package com.example;

                        /**
                         * Javadoc.
                         */
                        // TODO: @Remote removed \u2014 expose as REST API (e.g. @RestController)
                        public interface OrderServiceRemote
                            extends BaseService
                        {
                        }
                        """
                )
        );
    }

    @Test
    void marksRemoteInterfaceWithCrlfLineEndings() {
        // Windows CRLF files: the modifier prefix whitespace is "\r\n", not "\n".
        // The fix must use \R to match any line break, otherwise the blank line remains.
        // detectLineEnding() detects CRLF from the class declaration prefix and uses it
        // for the TextComment suffix, so the TODO line also ends with CRLF.
        String crlf = "\r\n";
        rewriteRun(
                java(
                        "package com.example;" + crlf +
                        crlf +
                        "import javax.ejb.Remote;" + crlf +
                        crlf +
                        "/**" + crlf +
                        " * Javadoc." + crlf +
                        " */" + crlf +
                        "@Remote" + crlf +
                        "public interface OrderServiceRemote {" + crlf +
                        "}" + crlf,

                        "package com.example;" + crlf +
                        crlf +
                        "/**" + crlf +
                        " * Javadoc." + crlf +
                        " */" + crlf +
                        "// TODO: @Remote removed \u2014 expose as REST API (e.g. @RestController)" + crlf +
                        "public interface OrderServiceRemote {" + crlf +
                        "}" + crlf
                )
        );
    }

    @Test
    void marksRemoteWhenFirstAnnotationAmongMultiple() {
        // When @Remote is the FIRST annotation and another follows (e.g. @Stateless),
        // the orphaned newline must be stripped from the next annotation's prefix,
        // not from a modifier \u2014 otherwise a spurious blank line appears between TODO and @Stateless.
        rewriteRun(
                java(
                        """
                        import javax.ejb.Remote;
                        import javax.ejb.Stateless;

                        @Remote
                        @Stateless
                        class OrderService {
                        }
                        """,
                        """
                        import javax.ejb.Stateless;

                        // TODO: @Remote removed \u2014 expose as REST API (e.g. @RestController)
                        @Stateless
                        class OrderService {
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
                                        .activateRecipes("hu.dojcsak.openrewrite.recipe.MarkRemoteEjbs"))
                        .parser(JavaParser.fromJavaVersion().classpath("javax.ejb-api")),
                java(
                        """
                        import javax.ejb.Remote;

                        @Remote
                        interface Foo {}
                        """,
                        """
                        // TODO: @Remote removed \u2014 expose as REST API (e.g. @RestController)
                        interface Foo {}
                        """
                )
        );
    }

    @Test
    void marksRemoteInterfaceWithCrlfAndJavadocNoImportNoModifier() {
        // Javadoc comment suffix "\r\n" is the only CRLF signal when prefix whitespace is empty
        // and there are no modifiers or imports. detectLineEnding must scan comment suffixes.
        String crlf = "\r\n";
        rewriteRun(
                java(
                        "/**" + crlf +
                        " * A remote interface." + crlf +
                        " */" + crlf +
                        "@javax.ejb.Remote" + crlf +
                        "interface Foo {}" + crlf,

                        "/**" + crlf +
                        " * A remote interface." + crlf +
                        " */" + crlf +
                        "// TODO: @javax.ejb.Remote removed — expose as REST API (e.g. @RestController)" + crlf +
                        "interface Foo {}" + crlf
                )
        );
    }

    @Test
    void doesNotMarkClassWithoutRemote() {
        rewriteRun(
                java(
                        """
                        import javax.ejb.Stateless;

                        @Stateless
                        class OrderService {
                        }
                        """
                )
        );
    }
}
