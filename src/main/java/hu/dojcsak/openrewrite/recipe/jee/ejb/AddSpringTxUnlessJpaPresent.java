package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.MavenTagInsertionComparator;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.xml.AddToTagVisitor;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptyList;
import static java.util.Collections.newSetFromMap;

/**
 * Adds {@code org.springframework:spring-tx} for EJB session bean modules that do not use JPA.
 * <p>
 * In OpenRewrite, all {@code ScanningRecipe} scan phases run on the <em>original</em> source files
 * before any recipe edit phases are applied. Therefore this recipe cannot scan for the
 * {@code @Transactional} import that {@link AddTransactionalToServiceBeans} will add in its edit
 * phase — it isn't there yet. Instead, it scans for the <em>original</em> EJB session bean
 * annotations ({@code @Stateless}, {@code @Singleton}) which are semantically equivalent: every
 * such bean will receive {@code @Transactional} after migration.
 * <p>
 * {@code spring-boot-starter-data-jpa} already provides {@code spring-tx} transitively, so this
 * recipe adds the direct dependency only when {@code javax.persistence.*} is absent, making the
 * two steps mutually exclusive.
 * <p>
 * The accumulator is keyed by module root path so that in a multi-module Maven project, JPA usage
 * in one module does not suppress {@code spring-tx} addition in unrelated non-JPA modules.
 * <p>
 * The {@code <version>} tag is omitted when {@code spring-tx} is already covered by a BOM in the
 * module's dependency management (e.g. {@code spring-boot-dependencies}), keeping the pom
 * consistent with other BOM-managed dependencies in the same project.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddSpringTxUnlessJpaPresent extends ScanningRecipe<AddSpringTxUnlessJpaPresent.Acc> {

    String displayName = "Add spring-tx for EJB session bean modules without JPA";

    String description = "Adds org.springframework:spring-tx when the module contains @Stateless or @Singleton " +
            "EJB session beans but does not use javax.persistence.* types. " +
            "Avoids redundancy with spring-boot-starter-data-jpa, which already provides spring-tx transitively. " +
            "The decision is made per module so that JPA usage in one module does not suppress the dependency in unrelated non-JPA modules. " +
            "The explicit version is omitted when spring-tx is already covered by a BOM in the module's dependency management.";

    public static class Acc {
        final Set<String> ejbModules = newSetFromMap(new ConcurrentHashMap<>());
        final Set<String> jpaModules = newSetFromMap(new ConcurrentHashMap<>());
    }

    @Override
    public Acc getInitialValue(ExecutionContext ctx) {
        return new Acc();
    }

    // Returns the module root by stripping the /src/main/java/ (or /src/test/java/) suffix.
    // Falls back to "" for paths that don't follow Maven conventions (e.g. in unit tests).
    private static String moduleRoot(Path sourcePath) {
        String path = sourcePath.toString().replace('\\', '/');
        int idx = path.indexOf("/src/main/java/");
        if (idx == -1) {
            idx = path.indexOf("/src/test/java/");
        }
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Acc acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final AnnotationMatcher statelessMatcher = new AnnotationMatcher("@javax.ejb.Stateless");
            private final AnnotationMatcher singletonMatcher = new AnnotationMatcher("@javax.ejb.Singleton");

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                if (statelessMatcher.matches(annotation) || singletonMatcher.matches(annotation)) {
                    J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                    acc.ejbModules.add(moduleRoot(cu.getSourcePath()));
                }
                return super.visitAnnotation(annotation, ctx);
            }

            @Override
            public J.Import visitImport(J.Import _import, ExecutionContext ctx) {
                if (_import.getTypeName().startsWith("javax.persistence.")) {
                    J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                    acc.jpaModules.add(moduleRoot(cu.getSourcePath()));
                }
                return super.visitImport(_import, ctx);
            }
        };
    }

    private static final XPathMatcher DEPENDENCIES_MATCHER = new XPathMatcher("/project/dependencies");

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Acc acc) {
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                Path pomPath = document.getSourcePath();
                String moduleRoot = pomPath.getParent() != null ?
                        pomPath.getParent().toString().replace('\\', '/') :
                        "";
                if (!acc.ejbModules.contains(moduleRoot) || acc.jpaModules.contains(moduleRoot)) {
                    return super.visitDocument(document, ctx);
                }
                ResolvedPom pom = getResolutionResult().getPom();
                if (pom.getRequestedDependencies().stream()
                        .anyMatch(d -> "org.springframework".equals(pom.getValue(d.getGroupId())) &&
                                "spring-tx".equals(pom.getValue(d.getArtifactId())))) {
                    return super.visitDocument(document, ctx);
                }
                boolean bomManaged = pom.getManagedDependency("org.springframework", "spring-tx", null, null) != null;
                Xml.Tag depTag = Xml.Tag.build(
                        "\n<dependency>\n" +
                        "<groupId>org.springframework</groupId>\n" +
                        "<artifactId>spring-tx</artifactId>\n" +
                        (bomManaged ? "" : "<version>5.3.39</version>\n") +
                        "</dependency>"
                );
                Xml.Document maven = super.visitDocument(document, ctx);
                Xml.Tag root = maven.getRoot();
                if (!root.getChild("dependencies").isPresent()) {
                    doAfterVisit(new AddToTagVisitor<>(root, Xml.Tag.build("<dependencies/>"),
                            new MavenTagInsertionComparator(root.getContent() == null ? emptyList() : root.getContent())));
                }
                // Append at end of <dependencies>: no comparator passed to AddToTagVisitor
                doAfterVisit(new MavenIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                        if (DEPENDENCIES_MATCHER.matches(getCursor())) {
                            doAfterVisit(new AddToTagVisitor<>(tag, depTag));
                            maybeUpdateModel();
                        }
                        return super.visitTag(tag, ctx);
                    }
                });
                return maven;
            }
        };
    }
}
