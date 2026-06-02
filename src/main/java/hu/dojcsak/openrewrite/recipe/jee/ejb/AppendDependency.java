package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
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
 * Appends a Maven dependency at the END of the {@code <dependencies>} block,
 * preserving the project's existing dependency order instead of inserting at the
 * alphabetically sorted position that {@link org.openrewrite.java.dependencies.AddDependency} uses.
 * <p>
 * The {@code <version>} tag is omitted when the version is already managed by
 * {@code <dependencyManagement>} or an imported BOM; otherwise the supplied {@code version}
 * value is written explicitly.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AppendDependency extends ScanningRecipe<AppendDependency.Acc> {

    String displayName = "Append Maven dependency at end of <dependencies>";

    String description = "Adds a Maven dependency to the end of the <dependencies> block, " +
            "preserving the project's existing ordering. Unlike AddDependency, no alphabetical " +
            "re-sorting is performed. The <version> tag is omitted when the version is already " +
            "managed by dependencyManagement or a BOM.";

    @Option(displayName = "Group ID",
            description = "The groupId of the dependency to add.",
            example = "org.springframework.boot")
    String groupId;

    @Option(displayName = "Artifact ID",
            description = "The artifactId of the dependency to add.",
            example = "spring-boot-starter")
    String artifactId;

    @Option(displayName = "Version",
            description = "The version of the dependency. Omitted when the version is already " +
                    "managed by dependencyManagement or a BOM.",
            example = "2.7.18",
            required = false)
    String version;

    @Option(displayName = "Only if using",
            description = "Only add the dependency when at least one source file in the module " +
                    "uses a type matching this pattern. Supports wildcard package patterns.",
            example = "javax.ejb.*",
            required = false)
    String onlyIfUsing;

    public static class Acc {
        // null  → add to all modules (onlyIfUsing not set)
        // non-null → only add to module roots recorded by the scanner
        final Set<String> moduleRoots;

        Acc(boolean track) {
            moduleRoots = track ? newSetFromMap(new ConcurrentHashMap<>()) : null;
        }
    }

    @Override
    public Acc getInitialValue(ExecutionContext ctx) {
        return new Acc(onlyIfUsing != null);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Acc acc) {
        if (onlyIfUsing == null) return TreeVisitor.noop();
        return Preconditions.check(
                new UsesType<>(onlyIfUsing, true),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                        acc.moduleRoots.add(moduleRoot(cu.getSourcePath()));
                        return cu;
                    }
                }
        );
    }

    private static final XPathMatcher DEPENDENCIES_MATCHER = new XPathMatcher("/project/dependencies");

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Acc acc) {
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                Path pomPath = document.getSourcePath();
                String modRoot = pomPath.getParent() != null ?
                        pomPath.getParent().toString().replace('\\', '/') : "";

                if (acc.moduleRoots != null && !acc.moduleRoots.contains(modRoot)) {
                    return super.visitDocument(document, ctx);
                }

                ResolvedPom pom = getResolutionResult().getPom();
                if (pom.getRequestedDependencies().stream()
                        .anyMatch(d -> groupId.equals(pom.getValue(d.getGroupId())) &&
                                artifactId.equals(pom.getValue(d.getArtifactId())))) {
                    return super.visitDocument(document, ctx);
                }

                // Omit <version> when the version is already managed by dependencyManagement or BOM
                boolean includeVersion = pom.getManagedVersion(groupId, artifactId, null, null) == null
                        && version != null;

                Xml.Document maven = super.visitDocument(document, ctx);
                Xml.Tag root = maven.getRoot();
                if (!root.getChild("dependencies").isPresent()) {
                    doAfterVisit(new AddToTagVisitor<>(root, Xml.Tag.build("<dependencies/>"),
                            new MavenTagInsertionComparator(
                                    root.getContent() == null ? emptyList() : root.getContent())));
                }
                doAfterVisit(new MavenIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                        // super first: children are visited on the unmodified tag
                        Xml.Tag t = super.visitTag(tag, ctx);
                        if (DEPENDENCIES_MATCHER.matches(getCursor())) {
                            String depPrefix = siblingDepPrefix(t);
                            String childPrefix = siblingChildPrefix(t, depPrefix);
                            Xml.Tag newDep = Xml.Tag.build(
                                    "<dependency>" +
                                    childPrefix + "<groupId>" + groupId + "</groupId>" +
                                    childPrefix + "<artifactId>" + artifactId + "</artifactId>" +
                                    (includeVersion ? childPrefix + "<version>" + version + "</version>" : "") +
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

    static String siblingDepPrefix(Xml.Tag depsTag) {
        List<? extends Content> content = depsTag.getContent();
        if (content != null) {
            for (Content c : content) {
                if (c instanceof Xml.Tag && "dependency".equals(((Xml.Tag) c).getName())) {
                    String prefix = ((Xml.Tag) c).getPrefix();
                    if (prefix.contains("\n")) {
                        // Normalize: strip any blank lines, keep only one newline + last-line indent
                        String nl = prefix.contains("\r\n") ? "\r\n" : "\n";
                        return nl + lastLineIndent(prefix);
                    }
                }
            }
        }
        String nl = depsTag.getPrefix().contains("\r\n") ? "\r\n" : "\n";
        String depsIndent = lastLineIndent(depsTag.getPrefix());
        return nl + depsIndent + depsIndent;
    }

    static String siblingChildPrefix(Xml.Tag depsTag, String depPrefix) {
        List<? extends Content> content = depsTag.getContent();
        if (content != null) {
            for (Content c : content) {
                if (c instanceof Xml.Tag && "dependency".equals(((Xml.Tag) c).getName())) {
                    List<? extends Content> children = ((Xml.Tag) c).getContent();
                    if (children != null) {
                        for (Content child : children) {
                            if (child instanceof Xml.Tag) {
                                String prefix = ((Xml.Tag) child).getPrefix();
                                if (prefix.contains("\n")) {
                                    // Normalize: strip any blank lines, keep only one newline + last-line indent
                                    String nl = prefix.contains("\r\n") ? "\r\n" : "\n";
                                    return nl + lastLineIndent(prefix);
                                }
                            }
                        }
                    }
                }
            }
        }
        String nl = depPrefix.contains("\r\n") ? "\r\n" : "\n";
        String depsIndent = lastLineIndent(depsTag.getPrefix());
        String depIndent = lastLineIndent(depPrefix);
        return nl + depIndent + depsIndent;
    }

    static String lastLineIndent(String prefix) {
        int lastLf = prefix.lastIndexOf('\n');
        if (lastLf < 0) return "";
        return prefix.substring(lastLf + 1).replace("\r", "");
    }

    // Returns the module root by stripping the /src/main/java/ (or /src/test/java/) suffix.
    private static String moduleRoot(Path sourcePath) {
        String path = sourcePath.toString().replace('\\', '/');
        int idx = path.indexOf("/src/main/java/");
        if (idx == -1) idx = path.indexOf("/src/test/java/");
        return idx >= 0 ? path.substring(0, idx) : "";
    }
}
