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
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * The {@code <version>} tag is always omitted: when {@code spring-tx} is covered by a BOM (e.g.
 * {@code spring-boot-dependencies}) no version is needed; when no BOM is present the user must
 * supply the correct version for their Spring Framework generation (5.x or 6.x) to avoid an
 * inadvertent downgrade or upgrade.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddSpringTxUnlessJpaPresent extends ScanningRecipe<AddSpringTxUnlessJpaPresent.Acc> {

    String displayName = "Add spring-tx for EJB session bean modules without JPA";

    String description = "Adds org.springframework:spring-tx when the module contains @Stateless or @Singleton " +
            "EJB session beans but does not use javax.persistence.* types. " +
            "Avoids redundancy with spring-boot-starter-data-jpa, which already provides spring-tx transitively. " +
            "The decision is made per module so that JPA usage in one module does not suppress the dependency in unrelated non-JPA modules. " +
            "The <version> tag is always omitted: BOM-managed projects need no explicit version, " +
            "and non-BOM projects must supply the version appropriate for their Spring Framework generation.";

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
                // AST-based fallback: when no version is provided and no BOM is present,
                // maybeUpdateModel() cannot resolve the dep → the cached model stays stale →
                // getRequestedDependencies() misses spring-tx in cycle 2. Reading from the AST
                // directly is always reliable.
                if (document.getRoot().getChild("dependencies").isPresent()) {
                    List<? extends Content> deps = document.getRoot().getChild("dependencies").get().getContent();
                    if (deps != null && deps.stream()
                            .filter(c -> c instanceof Xml.Tag && "dependency".equals(((Xml.Tag) c).getName()))
                            .map(c -> (Xml.Tag) c)
                            .anyMatch(dep ->
                                    "org.springframework".equals(dep.getChildValue("groupId").orElse("")) &&
                                    "spring-tx".equals(dep.getChildValue("artifactId").orElse("")))) {
                        return super.visitDocument(document, ctx);
                    }
                }
                Xml.Document maven = super.visitDocument(document, ctx);
                Xml.Tag root = maven.getRoot();
                if (!root.getChild("dependencies").isPresent()) {
                    doAfterVisit(new AddToTagVisitor<>(root, Xml.Tag.build("<dependencies/>"),
                            new MavenTagInsertionComparator(root.getContent() == null ? emptyList() : root.getContent())));
                }
                doAfterVisit(new MavenIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                        // super first: children are visited on the unmodified tag
                        Xml.Tag t = super.visitTag(tag, ctx);
                        if (DEPENDENCIES_MATCHER.matches(getCursor())) {
                            String depPrefix = AppendDependency.siblingDepPrefix(t);
                            String childPrefix = AppendDependency.siblingChildPrefix(t, depPrefix);
                            Xml.Tag newDep = Xml.Tag.build(
                                    "<dependency>" +
                                    childPrefix + "<groupId>org.springframework</groupId>" +
                                    childPrefix + "<artifactId>spring-tx</artifactId>" +
                                    depPrefix + "</dependency>"
                            ).withPrefix(depPrefix);
                            List<Content> content = new ArrayList<>(
                                    t.getContent() == null ? emptyList() : t.getContent());
                            content.add(newDep);
                            t = t.withContent(content);
                            maybeUpdateModel();
                        }
                        return t;
                    }
                });
                return maven;
            }
        };
    }
}
