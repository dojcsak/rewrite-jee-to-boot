package hu.dojcsak.openrewrite.recipe.jee.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

class InlineLocalBeanInterfacesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new InlineLocalBeanInterfaces())
                .parser(JavaParser.fromJavaVersion().classpath("javax.ejb-api"));
    }

    @DocumentExample
    @Test
    void inlinesBareLocalInterfaceWithSingleImplementor() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                public class FooBean implements FooLocal {
                                    public void doWork() {}
                                }
                                """,
                        """
                                public class FooBean {
                                    public void doWork() {}
                                }
                                """
                ),
                java(
                        """
                                public class Consumer {
                                    private FooLocal foo;
                                }
                                """,
                        """
                                public class Consumer {
                                    private FooBean foo;
                                }
                                """
                )
        );
    }

    @Test
    void inlinesClassLevelLocalAnnotationOnAbstractBase() {
        rewriteRun(
                java(
                        """
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                import javax.ejb.Local;

                                @Local({FooLocal.class})
                                public abstract class FooBase {
                                    public abstract void doWork();
                                }
                                """,
                        """
                                import javax.ejb.Local;

                                @Local({FooBean.class})
                                public abstract class FooBase {
                                    public abstract void doWork();
                                }
                                """
                ),
                java(
                        """
                                public class FooBean extends FooBase implements FooLocal {
                                    public void doWork() {}
                                }
                                """,
                        """
                                public class FooBean extends FooBase {
                                    public void doWork() {}
                                }
                                """
                ),
                java(
                        """
                                public class Consumer {
                                    private FooLocal foo;
                                }
                                """,
                        """
                                public class Consumer {
                                    private FooBean foo;
                                }
                                """
                )
        );
    }

    @Test
    void stripsImplementsClauseFromAbstractIntermediateClassThatDeclaresItDirectly() {
        // FooAbstract - not FooBean - is the one that textually declares `implements FooLocal`;
        // FooBean only inherits it transitively by extending FooAbstract. FooAbstract is still the
        // sole (indirect) implementor's ancestor, so once FooLocal is fully inlined, the
        // `implements FooLocal` clause on FooAbstract - which is neither the sole concrete
        // implementor's own file nor a field/param/return-type reference site - must still be
        // stripped, otherwise it would dangle after FooLocal.java is deleted.
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                public abstract class FooAbstract implements FooLocal {
                                }
                                """,
                        """
                                public abstract class FooAbstract {
                                }
                                """
                ),
                java(
                        """
                                public class FooBean extends FooAbstract {
                                    public void doWork() {}
                                }
                                """
                )
        );
    }

    @Test
    void retypesGenericAndArrayTypedReferences() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                public class FooBean implements FooLocal {
                                    public void doWork() {}
                                }
                                """,
                        """
                                public class FooBean {
                                    public void doWork() {}
                                }
                                """
                ),
                java(
                        """
                                import java.util.List;

                                public class Consumer {
                                    private List<FooLocal> handlers;
                                    private FooLocal[] items;
                                }
                                """,
                        """
                                import java.util.List;

                                public class Consumer {
                                    private List<FooBean> handlers;
                                    private FooBean[] items;
                                }
                                """
                )
        );
    }

    @Test
    void flagsUnresolvableParameterAtTheEnclosingMethodInsteadOfMidSignature() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                }
                                """
                ),
                java(
                        """
                                public class FooBean implements FooLocal {
                                }
                                """,
                        spec -> spec.path("moduleBusiness/src/main/java/FooBean.java")
                                .markers(new JavaSourceSet(Tree.randomId(), "main", Collections.emptyList(), Collections.emptyMap()))
                ),
                java(
                        """
                                public class Consumer {
                                    void handle(String name, FooLocal foo) {
                                    }
                                }
                                """,
                        """
                                public class Consumer {
                                    // TODO: could not inline @Local interface 'FooLocal' to 'FooBean' - target class is not visible from this module/classpath; migrate the field type manually
                                    void handle(String name, FooLocal foo) {
                                    }
                                }
                                """,
                        spec -> spec.path("moduleConsumer/src/main/java/Consumer.java")
                                .markers(new JavaSourceSet(Tree.randomId(), "main", Collections.emptyList(), Collections.emptyMap()))
                )
        );
    }

    @Test
    void doesNotInlineWhenImplementorAlsoImplementsRemote() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """
                ),
                java(
                        """
                                import javax.ejb.Remote;

                                @Remote
                                public interface FooRemote {
                                    void doWork();
                                }
                                """
                ),
                java(
                        """
                                public class FooBean implements FooLocal, FooRemote {
                                    public void doWork() {}
                                }
                                """
                )
        );
    }

    @Test
    void doesNotInlineWhenNoImplementorFoundInSourceSet() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """
                )
        );
    }

    @Test
    void doesNotInlineInterfaceExtendedByAnotherInterface() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface BaseLocal {
                                    void doWork();
                                }
                                """
                ),
                java(
                        """
                                public interface SubLocal extends BaseLocal {
                                }
                                """
                ),
                java(
                        """
                                public class FooBean implements SubLocal {
                                    public void doWork() {}
                                }
                                """
                )
        );
    }

    @Test
    void doesNotInlineWhenMultipleImplementorsExist() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """
                ),
                java(
                        """
                                public class FooBeanA implements FooLocal {
                                    public void doWork() {}
                                }
                                """
                ),
                java(
                        """
                                public class FooBeanB implements FooLocal {
                                    public void doWork() {}
                                }
                                """
                )
        );
    }

    @Test
    void stripsOnlyTargetInterfaceFromMultipleImplements() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                public interface FooWSInterface {
                                    void doWork();
                                }
                                """
                ),
                java(
                        """
                                public class FooBean implements FooLocal, FooWSInterface {
                                    public void doWork() {}
                                }
                                """,
                        """
                                public class FooBean implements FooWSInterface {
                                    public void doWork() {}
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
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """
                ),
                java(
                        """
                                public class FooBean implements FooLocal {
                                    public void doWork() {}
                                }
                                """
                ),
                java(
                        """
                                public class FooLookup {
                                    String jndiName() {
                                        return "java:comp/env/ejb/FooLocal";
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void doesNotRetypeUnresolvableCrossModuleReferenceAndKeepsInterface() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        spec -> spec.path("moduleInterface/src/main/java/FooLocal.java")
                ),
                java(
                        """
                                public class FooBean implements FooLocal {
                                    public void doWork() {}
                                }
                                """,
                        spec -> spec.path("moduleBusiness/src/main/java/FooBean.java")
                                .markers(new JavaSourceSet(Tree.randomId(), "main", Collections.emptyList(), Collections.emptyMap()))
                ),
                java(
                        """
                                public class Consumer {
                                    private FooLocal foo;
                                }
                                """,
                        """
                                public class Consumer {
                                    // TODO: could not inline @Local interface 'FooLocal' to 'FooBean' - target class is not visible from this module/classpath; migrate the field type manually
                                    private FooLocal foo;
                                }
                                """,
                        spec -> spec.path("moduleConsumer/src/main/java/Consumer.java")
                                .markers(new JavaSourceSet(Tree.randomId(), "main", Collections.emptyList(), Collections.emptyMap()))
                )
        );
    }

    @Test
    void retypesCrossModuleReferenceWhenInterfaceAndImplementorShareAModule() {
        // Mirrors a real-world multi-module Maven reactor shape (business module with a genuine
        // compile dependency on a sibling integration module that hosts both the @Local interface
        // and its sole implementor): the referencing CU's recorded classpath never lists the
        // implementor's FQN (an @Local call site only ever names the interface), but the interface
        // and implementor being co-located in one module proves the dependency is real.
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null,
                        spec -> spec.path("moduleShared/src/main/java/FooLocal.java")
                ),
                java(
                        """
                                public class FooBean implements FooLocal {
                                    public void doWork() {}
                                }
                                """,
                        """
                                public class FooBean {
                                    public void doWork() {}
                                }
                                """,
                        spec -> spec.path("moduleShared/src/main/java/FooBean.java")
                ),
                java(
                        """
                                public class Consumer {
                                    private FooLocal foo;
                                }
                                """,
                        """
                                public class Consumer {
                                    private FooBean foo;
                                }
                                """,
                        spec -> spec.path("moduleConsumer/src/main/java/Consumer.java")
                                .markers(new JavaSourceSet(Tree.randomId(), "main", Collections.emptyList(), Collections.emptyMap()))
                )
        );
    }

    @Test
    void composesWithMigrateStatelessSessionBeansToFullyCleanUpClassLevelLocalAnnotation() {
        // Documented composition order: InlineLocalBeanInterfaces retypes the class-literal
        // reference on the abstract Base (FooLocal.class -> FooBean.class); MigrateStatelessSessionBeans
        // then strips the now-pointless @Local entirely, since FooBase never carries
        // @Stateless/@Singleton itself.
        rewriteRun(
                spec -> spec.recipes(new InlineLocalBeanInterfaces(), new MigrateStatelessSessionBeans())
                        .parser(JavaParser.fromJavaVersion().classpath("javax.ejb-api", "spring-context")),
                java(
                        """
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                import javax.ejb.Local;

                                @Local({FooLocal.class})
                                public abstract class FooBase {
                                    public abstract void doWork();
                                }
                                """,
                        """
                                public abstract class FooBase {
                                    public abstract void doWork();
                                }
                                """
                ),
                java(
                        """
                                import javax.ejb.Stateless;

                                @Stateless
                                public class FooBean extends FooBase implements FooLocal {
                                    public void doWork() {}
                                }
                                """,
                        """
                                import org.springframework.stereotype.Service;

                                @Service
                                public class FooBean extends FooBase {
                                    public void doWork() {}
                                }
                                """
                )
        );
    }

    @Test
    void isResolvableFromReturnsTrueWhenNoSourceSetMarkerPresent() {
        InlineLocalBeanInterfaces.Acc acc = new InlineLocalBeanInterfaces.Acc();
        Path referencingCu = Paths.get("moduleA/src/main/java/Consumer.java");
        assertThat(InlineLocalBeanInterfaces.isResolvableFrom(referencingCu, "pkg.FooLocal", "pkg.FooBean", acc)).isTrue();
    }

    @Test
    void isResolvableFromChecksClasspathThenModuleRootFallback() {
        InlineLocalBeanInterfaces.Acc acc = new InlineLocalBeanInterfaces.Acc();
        Path referencingCu = Paths.get("moduleA/src/main/java/Consumer.java");
        Path sameModuleImplementor = Paths.get("moduleA/src/main/java/pkg/FooBean.java");
        Path otherModuleImplementor = Paths.get("moduleB/src/main/java/pkg/FooBean.java");
        acc.cuHasSourceSetMarker.add(referencingCu);
        acc.cuClasspathFqns.put(referencingCu, Collections.emptyList());
        // "pkg.FooLocal" is intentionally never added to acc.typeFqnToPath below, so the
        // same-module-as-interface shortcut can't fire here and this test keeps isolating the
        // classpath/moduleRoot-fallback branches (see isResolvableFromShortCircuitsWhen... below).

        // Not on classpath, but same module root -> resolvable via the path fallback.
        acc.typeFqnToPath.put("pkg.FooBean", sameModuleImplementor);
        assertThat(InlineLocalBeanInterfaces.isResolvableFrom(referencingCu, "pkg.FooLocal", "pkg.FooBean", acc)).isTrue();

        // Not on classpath, different module root -> unresolvable.
        acc.typeFqnToPath.put("pkg.FooBean", otherModuleImplementor);
        assertThat(InlineLocalBeanInterfaces.isResolvableFrom(referencingCu, "pkg.FooLocal", "pkg.FooBean", acc)).isFalse();

        // Present directly in the recorded classpath -> resolvable regardless of module root.
        acc.cuClasspathFqns.put(referencingCu, List.of("pkg.FooBean"));
        assertThat(InlineLocalBeanInterfaces.isResolvableFrom(referencingCu, "pkg.FooLocal", "pkg.FooBean", acc)).isTrue();
    }

    @Test
    void isResolvableFromShortCircuitsWhenInterfaceAndImplementorShareModuleRoot() {
        InlineLocalBeanInterfaces.Acc acc = new InlineLocalBeanInterfaces.Acc();
        Path referencingCu = Paths.get("moduleConsumer/src/main/java/Consumer.java");
        Path interfacePath = Paths.get("moduleShared/src/main/java/pkg/FooLocal.java");
        Path implementorPath = Paths.get("moduleShared/src/main/java/pkg/FooBean.java");
        acc.cuHasSourceSetMarker.add(referencingCu);
        // The implementor's FQN never appears in the referencing CU's recorded classpath - mirroring
        // the real-world case where @Local call sites only ever mention the interface, never the
        // concrete impl, so the implementor is never lazily loaded into the JavaSourceSet classpath
        // list even though a real, direct, compile-scope Maven dependency on its module exists.
        acc.cuClasspathFqns.put(referencingCu, Collections.emptyList());
        acc.typeFqnToPath.put("pkg.FooLocal", interfacePath);
        acc.typeFqnToPath.put("pkg.FooBean", implementorPath);

        // Referencing module is a genuinely different directory than either the interface's or the
        // implementor's module, yet resolvable: the interface and implementor are co-located, and the
        // reference site resolving the interface already proves a working dependency on that module.
        assertThat(InlineLocalBeanInterfaces.isResolvableFrom(referencingCu, "pkg.FooLocal", "pkg.FooBean", acc)).isTrue();
    }

    @Test
    void isResolvableFromDoesNotShortCircuitWhenInterfaceAndImplementorAreInDifferentModules() {
        InlineLocalBeanInterfaces.Acc acc = new InlineLocalBeanInterfaces.Acc();
        Path referencingCu = Paths.get("moduleConsumer/src/main/java/Consumer.java");
        Path interfacePath = Paths.get("moduleInterface/src/main/java/pkg/FooLocal.java");
        Path implementorPath = Paths.get("moduleBusiness/src/main/java/pkg/FooBean.java");
        acc.cuHasSourceSetMarker.add(referencingCu);
        acc.cuClasspathFqns.put(referencingCu, Collections.emptyList());
        acc.typeFqnToPath.put("pkg.FooLocal", interfacePath);
        acc.typeFqnToPath.put("pkg.FooBean", implementorPath);

        // Interface and implementor are known but declared in two different modules, and the
        // referencing module matches neither - the shortcut must not fire, and the classpath/
        // moduleRoot-fallback logic (both of which fail here) must still report unresolvable.
        assertThat(InlineLocalBeanInterfaces.isResolvableFrom(referencingCu, "pkg.FooLocal", "pkg.FooBean", acc)).isFalse();
    }

    // A manually indented multi-line extends/implements layout (common in Andromda-generated EJB
    // code) should survive interface removal from the implements clause unchanged. Note: a real-world
    // instance of this recipe suite losing that indentation was confirmed via the actual
    // rewrite-maven-plugin execution path against a real multi-module project, not reproducible under
    // this test harness's plain JavaParser - so this test documents the desired property rather than
    // proving root cause or guarding the specific regression that was found and fixed elsewhere
    // (MigrateStatelessSessionBeans/AddTransactionalToServiceBeans's JavaTemplate.apply() calls).
    @Test
    void preservesExtendsImplementsIndentationWhenStrippingLocalInterface() {
        rewriteRun(
                java(
                        """
                                import javax.ejb.Local;

                                @Local
                                public interface FooLocal {
                                    void doWork();
                                }
                                """,
                        (String) null
                ),
                java(
                        """
                                public class FooBase {
                                }
                                """
                ),
                java(
                        """
                                public interface FooOtherInterface {
                                }
                                """
                ),
                java(
                        """
                                public class FooBean
                                        extends FooBase
                                        implements FooLocal, FooOtherInterface {
                                    public void doWork() {}
                                }
                                """,
                        """
                                public class FooBean
                                        extends FooBase
                                        implements FooOtherInterface {
                                    public void doWork() {}
                                }
                                """
                )
        );
    }
}
