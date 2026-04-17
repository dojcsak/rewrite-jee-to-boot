package hu.dojcsak.openrewrite.recipe.jee.jpa;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;

/**
 * Adds {@code spring-boot-starter-data-jpa} and {@code h2} (runtime) to a pom.xml file
 * when the Maven module it represents contains Java sources that use {@code javax.persistence.*}.
 *
 * <p>The module boundary is derived from the source path: everything before the {@code src/}
 * segment is the module root. Only pom.xml files whose parent directory matches a module root
 * that has JPA usage receive the new dependencies — matching the per-module semantics of
 * {@code AddDependency}'s {@code onlyIfUsing} parameter.
 *
 * <p>Versions are intentionally omitted: the Spring Boot BOM (added by
 * {@code ConfigureSpringBootMaven}) manages both artifacts transitively.
 *
 * <p>Unlike {@link org.openrewrite.java.dependencies.AddDependency}, this recipe detects
 * the existing indentation style and reproduces it exactly, bypassing
 * {@code AddToTagVisitor.autoFormat()} which always falls back to a 3-space default.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddJpaStarterDependencies
        extends ScanningRecipe<AddJpaStarterDependencies.Acc> {

    private static final String STARTER_GROUP    = "org.springframework.boot";
    private static final String STARTER_ARTIFACT = "spring-boot-starter-data-jpa";

    private static final String H2_GROUP    = "com.h2database";
    private static final String H2_ARTIFACT = "h2";

    // -------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------

    public static class Acc {
        /**
         * Module roots (normalised with '/' separators) whose source tree contains
         * at least one {@code javax.persistence.*} import.
         * E.g. {@code "ctm-main/ctm-main-persistence"}.
         * An empty string represents the project root module.
         */
        final Set<String> moduleRootsWithJpa = new HashSet<>();
    }

    // -------------------------------------------------------------------------
    // Recipe metadata
    // -------------------------------------------------------------------------

    @Override
    public String getDisplayName() {
        return "Add Spring Boot JPA starter and H2 dependencies";
    }

    @Override
    public String getDescription() {
        return "Adds spring-boot-starter-data-jpa and H2 runtime dependency to a pom.xml file " +
               "when the Maven module it represents contains Java sources that use " +
               "javax.persistence.* types. Version management is delegated to the Spring Boot BOM.";
    }

    // -------------------------------------------------------------------------
    // ScanningRecipe
    // -------------------------------------------------------------------------

    @Override
    public Acc getInitialValue(ExecutionContext ctx) {
        return new Acc();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Acc acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                boolean usesJpa = cu.getImports().stream()
                        .anyMatch(imp -> imp.getTypeName().startsWith("javax.persistence"));
                if (usesJpa) {
                    acc.moduleRootsWithJpa.add(moduleRoot(cu.getSourcePath()));
                }
                return cu;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Acc acc) {
        if (acc.moduleRootsWithJpa.isEmpty()) return TreeVisitor.noop();
        return new XmlIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                Xml.Document d = super.visitDocument(document, ctx);
                if (!isPomXml(d)) return d;

                Path pomParent = d.getSourcePath().getParent();
                String moduleDir = pomParent != null
                        ? pomParent.toString().replace('\\', '/') : "";
                if (!acc.moduleRootsWithJpa.contains(moduleDir)) return d;

                doAfterVisit(new RawDepAdder(STARTER_GROUP, STARTER_ARTIFACT, null, null));
                doAfterVisit(new RawDepAdder(H2_GROUP, H2_ARTIFACT, null, "runtime"));
                return d;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    private static boolean isPomXml(Xml.Document doc) {
        Path fn = doc.getSourcePath().getFileName();
        return fn != null && "pom.xml".equals(fn.toString());
    }

    /**
     * Derives the module root from a Java source path by finding the {@code src/} segment
     * and taking everything before it.
     * E.g. {@code ctm-main/ctm-main-persistence/src/main/java/...} →
     *      {@code "ctm-main/ctm-main-persistence"}.
     * Returns {@code ""} for files directly under the project root.
     */
    static String moduleRoot(Path sourcePath) {
        for (int i = 0; i < sourcePath.getNameCount(); i++) {
            if ("src".equals(sourcePath.getName(i).toString())) {
                return i == 0 ? "" : sourcePath.subpath(0, i).toString().replace('\\', '/');
            }
        }
        Path parent = sourcePath.getParent();
        return parent != null ? parent.toString().replace('\\', '/') : "";
    }

    // -------------------------------------------------------------------------
    // Indentation helpers — same logic as ConfigureSpringBootMaven.
    // Kept inline to avoid coupling between sub-packages.
    // -------------------------------------------------------------------------

    static class Indent {
        final String tag;
        final String child;

        Indent(String tag, String child) {
            this.tag   = tag;
            this.child = child;
        }
    }

    /**
     * Detects indentation from existing siblings named {@code siblingName}.
     * Falls back to the parent tag's own indent + detected unit when no siblings exist.
     */
    static Indent detectIndent(Xml.Tag parent, String siblingName) {
        List<Xml.Tag> siblings = parent.getChildren(siblingName);
        if (!siblings.isEmpty()) {
            Xml.Tag first    = siblings.get(0);
            String tagPfx   = first.getPrefix();
            String childPfx = first.getContent() == null ? tagPfx + "    " :
                    first.getContent().stream()
                            .filter(c -> c instanceof Xml.Tag)
                            .map(c -> ((Xml.Tag) c).getPrefix())
                            .findFirst()
                            .orElse(tagPfx + "    ");
            return new Indent(tagPfx, childPfx);
        }
        String parentIndent = parent.getPrefix().replaceAll("^[\\r\\n]+", "");
        String unit = detectUnit(parent);
        return new Indent("\n" + parentIndent + unit, "\n" + parentIndent + unit + unit);
    }

    /** Detects the indent unit (e.g. {@code "    "}) from a tag's direct children. */
    static String detectUnit(Xml.Tag tag) {
        String tagIndent = tag.getPrefix().replaceAll("^[\\r\\n]+", "");
        if (tag.getContent() == null) return "    ";
        return tag.getContent().stream()
                .filter(c -> c instanceof Xml.Tag)
                .map(c -> ((Xml.Tag) c).getPrefix().replaceAll("^[\\r\\n]+", ""))
                .filter(s -> s.length() > tagIndent.length())
                .findFirst()
                .map(s -> s.substring(tagIndent.length()))
                .orElse("    ");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Xml.Tag appendChild(Xml.Tag parent, Xml.Tag newChild) {
        List newContent = parent.getContent() != null
                ? new ArrayList(parent.getContent())
                : new ArrayList();
        newContent.add(newChild);
        return parent.withContent(newContent);
    }

    /**
     * Inserts {@code newChild} before the first child tag whose name appears in
     * {@code beforeTagNames}, or appends it at the end if none are found.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Xml.Tag insertBefore(Xml.Tag parent, Xml.Tag newChild, String... beforeTagNames) {
        Set<String> stopAt = new HashSet<>(Arrays.asList(beforeTagNames));
        List existing = parent.getContent() != null ? parent.getContent() : Collections.emptyList();
        List result = new ArrayList();
        boolean inserted = false;
        for (Object item : existing) {
            if (!inserted && item instanceof Xml.Tag && stopAt.contains(((Xml.Tag) item).getName())) {
                result.add(newChild);
                inserted = true;
            }
            result.add(item);
        }
        if (!inserted) result.add(newChild);
        return parent.withContent(result);
    }

    // -------------------------------------------------------------------------
    // Raw dependency adder — never calls AddToTagVisitor or autoFormat, so the
    // indentation we detect from existing siblings is always preserved.
    //
    // CreateOrInsertDep handles both cases in a single pass:
    //   - /project with no <dependencies>: builds the full block from scratch
    //   - /project/dependencies already present: appends the <dependency> inside it
    // -------------------------------------------------------------------------

    private static class RawDepAdder extends XmlIsoVisitor<ExecutionContext> {

        private static final XPathMatcher PROJECT      = new XPathMatcher("/project");
        private static final XPathMatcher PROJECT_DEPS = new XPathMatcher("/project/dependencies");

        private final String groupId;
        private final String artifactId;
        @Nullable private final String version;
        @Nullable private final String scope;

        RawDepAdder(String groupId, String artifactId,
                    @Nullable String version, @Nullable String scope) {
            this.groupId    = groupId;
            this.artifactId = artifactId;
            this.version    = version;
            this.scope      = scope;
        }

        @Override
        public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
            Xml.Document d = super.visitDocument(document, ctx);
            if (alreadyPresent(d.getRoot())) return d;
            doAfterVisit(new CreateOrInsertDep());
            return d;
        }

        private boolean alreadyPresent(Xml.Tag root) {
            return root.getChild("dependencies")
                    .map(deps -> deps.getChildren("dependency").stream()
                            .anyMatch(dep ->
                                    groupId.equals(dep.getChildValue("groupId").orElse(""))
                                    && artifactId.equals(dep.getChildValue("artifactId").orElse(""))))
                    .orElse(false);
        }

        private class CreateOrInsertDep extends XmlIsoVisitor<ExecutionContext> {

            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = super.visitTag(tag, ctx);

                // Case A: <dependencies> absent — build the full block from scratch using
                // detectUnit() so we never touch autoFormat and the indent is always correct.
                if (PROJECT.matches(getCursor()) && !t.getChild("dependencies").isPresent()) {
                    String pi = t.getPrefix().replaceAll("^[\\r\\n]+", "");
                    String u  = detectUnit(t);
                    String l1 = "\n" + pi + u;
                    String l2 = "\n" + pi + u + u;
                    String l3 = "\n" + pi + u + u + u;

                    StringBuilder xml = new StringBuilder("<dependencies>");
                    xml.append(l2).append("<dependency>")
                       .append(l3).append("<groupId>").append(groupId).append("</groupId>")
                       .append(l3).append("<artifactId>").append(artifactId).append("</artifactId>");
                    if (version != null) {
                        xml.append(l3).append("<version>").append(version).append("</version>");
                    }
                    if (scope != null) {
                        xml.append(l3).append("<scope>").append(scope).append("</scope>");
                    }
                    xml.append(l2).append("</dependency>")
                       .append(l1).append("</dependencies>");

                    return insertBefore(t, Xml.Tag.build(xml.toString()).withPrefix(l1),
                            "build", "reporting", "profiles");
                }

                // Case B: <dependencies> present — append the <dependency> by detecting indent
                // from the existing siblings so the new entry matches their style exactly.
                if (PROJECT_DEPS.matches(getCursor())) {
                    if (t.getChildren("dependency").stream()
                            .anyMatch(d -> groupId.equals(d.getChildValue("groupId").orElse(""))
                                    && artifactId.equals(d.getChildValue("artifactId").orElse("")))) {
                        return t;
                    }
                    Indent indent = detectIndent(t, "dependency");
                    StringBuilder xml = new StringBuilder("<dependency>");
                    xml.append(indent.child).append("<groupId>").append(groupId).append("</groupId>")
                       .append(indent.child).append("<artifactId>").append(artifactId).append("</artifactId>");
                    if (version != null) {
                        xml.append(indent.child).append("<version>").append(version).append("</version>");
                    }
                    if (scope != null) {
                        xml.append(indent.child).append("<scope>").append(scope).append("</scope>");
                    }
                    xml.append(indent.tag).append("</dependency>");
                    return appendChild(t, Xml.Tag.build(xml.toString()).withPrefix(indent.tag));
                }

                return t;
            }
        }
    }
}
