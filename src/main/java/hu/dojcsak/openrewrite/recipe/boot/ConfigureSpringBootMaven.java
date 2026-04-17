package hu.dojcsak.openrewrite.recipe.boot;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.jspecify.annotations.Nullable;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.MavenTagInsertionComparator;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.xml.AddToTagVisitor;
import org.openrewrite.xml.RemoveContentVisitor;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static hu.dojcsak.openrewrite.recipe.boot.AddSpringBootApplicationFiles.hasFileName;

/**
 * Configures Maven pom files for Spring Boot in single-module and multi-module projects.
 *
 * <p><b>Single-module:</b>
 * <ul>
 *   <li>Adds {@code spring-boot-starter} and {@code spring-boot-starter-test} to the single pom.</li>
 *   <li>Adds {@code spring-boot-maven-plugin} (with version) to {@code <build><plugins>}.</li>
 *   <li>Changes packaging from {@code war}/{@code ear} to {@code jar}.</li>
 * </ul>
 *
 * <p><b>Multi-module:</b>
 * <ul>
 *   <li>Root pom: adds {@code spring-boot-maven-plugin} to
 *       {@code <build><pluginManagement><plugins>} (with version).</li>
 *   <li>Target modules (ear modules + war modules that are <em>not</em> listed as a dependency
 *       in any ear module): adds {@code spring-boot-starter}, {@code spring-boot-starter-test},
 *       activates the plugin in {@code <build><plugins>} (without version, inherited from root),
 *       and changes packaging from {@code war}/{@code ear} to {@code jar}.</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ConfigureSpringBootMaven
        extends ScanningRecipe<ConfigureSpringBootMaven.Accumulator> {

    String springBootVersion;

    private static final String PLUGIN_GROUP_ID    = "org.springframework.boot";
    private static final String PLUGIN_ARTIFACT_ID = "spring-boot-maven-plugin";

    private static final String EAR_PLUGIN_GROUP_ID    = "org.apache.maven.plugins";
    private static final String EAR_PLUGIN_ARTIFACT_ID = "maven-ear-plugin";

    private static final String WAR_PLUGIN_GROUP_ID    = "org.apache.maven.plugins";
    private static final String WAR_PLUGIN_ARTIFACT_ID = "maven-war-plugin";

    private static final String VERSION_PROPERTY = "spring-boot.version";
    private static final String VERSION_PROP_REF  = "${" + VERSION_PROPERTY + "}";

    // -------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------

    public static class Accumulator {
        /** All pom.xml files found, keyed by source path. */
        final Map<Path, PomModule> modules = new LinkedHashMap<>();
    }

    /** Minimal information extracted from a single pom.xml. */
    static class PomModule {
        final Path pomPath;
        final String groupId;
        final String artifactId;
        final String packaging;
        /** {@code groupId:artifactId} coordinates of each {@code <dependency>} in this pom. */
        final Set<String> depCoords;

        PomModule(Path pomPath, String groupId, String artifactId,
                  String packaging, Set<String> depCoords) {
            this.pomPath    = pomPath;
            this.groupId    = groupId;
            this.artifactId = artifactId;
            this.packaging  = packaging;
            this.depCoords  = depCoords;
        }
    }

    // -------------------------------------------------------------------------
    // Recipe metadata
    // -------------------------------------------------------------------------

    @Override
    public String getDisplayName() {
        return "Configure Spring Boot Maven setup";
    }

    @Override
    public String getDescription() {
        return "Adds spring-boot-starter, spring-boot-starter-test and the spring-boot-maven-plugin " +
               "to the correct Maven modules, and changes packaging from war/ear to jar for target modules. " +
               "Removes maven-ear-plugin from target module build/plugins (the EAR assembly is no longer needed " +
               "after the packaging change) and from the root pom's pluginManagement. " +
               "Removes maven-war-plugin from standalone WAR target modules (the WAR packaging is no longer " +
               "produced after the packaging change to jar). " +
               "In single-module projects everything goes to the single pom (plugin with version). " +
               "In multi-module projects the Spring Boot plugin is placed in the root pom's pluginManagement (with version) " +
               "and activated (plus starters added) only in ear modules and war modules that are not " +
               "listed as a dependency in any ear module (plugin without version, inherited from root).";
    }

    // -------------------------------------------------------------------------
    // ScanningRecipe
    // -------------------------------------------------------------------------

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        // MavenIsoVisitor resolves Maven property expressions (e.g. ${project.groupId},
        // ${application.id}) so that war-in-ear detection works even when pom files
        // use property interpolation for groupId / artifactId values.
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                ResolvedPom resolvedPom = getResolutionResult().getPom();

                String groupId = resolvedPom.getGroupId();
                if (groupId == null || groupId.isEmpty()) {
                    // Child poms that inherit groupId from parent
                    groupId = resolvedPom.getValue("${project.parent.groupId}");
                }
                if (groupId == null) groupId = "";

                String artifactId = resolvedPom.getArtifactId() != null
                        ? resolvedPom.getArtifactId() : "";
                String packaging  = resolvedPom.getPackaging(); // never null; defaults to "jar"

                Set<String> depCoords = new LinkedHashSet<>();
                for (Dependency dep : resolvedPom.getRequestedDependencies()) {
                    String g = resolvedPom.getValue(dep.getGroupId());
                    String a = resolvedPom.getValue(dep.getArtifactId());
                    if (g != null && a != null && !g.isEmpty() && !a.isEmpty()) {
                        depCoords.add(g + ":" + a);
                    }
                }

                acc.modules.put(document.getSourcePath(),
                        new PomModule(document.getSourcePath(), groupId, artifactId, packaging, depCoords));
                return document;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.modules.isEmpty()) return TreeVisitor.noop();

        Path rootPomPath         = detectRootPomPath(acc.modules);
        boolean multiModule      = acc.modules.size() > 1;
        Set<Path> targetPomPaths = resolveTargetPomPaths(acc.modules, multiModule);

        // Compute target module directories for weblogic-application.xml deletion.
        // A null parent means the pom is at the project root (single-module case).
        Set<Path> targetModuleDirs = new LinkedHashSet<>();
        boolean rootModuleIsTarget = false;
        for (Path pomPath : targetPomPaths) {
            Path dir = pomPath.getParent();
            if (dir == null) {
                rootModuleIsTarget = true;
            } else {
                targetModuleDirs.add(dir);
            }
        }
        final boolean rootIsTarget = rootModuleIsTarget;

        return new XmlIsoVisitor<ExecutionContext>() {
            @Override
            public @org.jspecify.annotations.Nullable Xml visit(
                    @org.jspecify.annotations.Nullable Tree tree, ExecutionContext ctx) {
                // Delete weblogic-application.xml from target modules by returning null.
                // The EAR deployment descriptor is obsolete after the packaging change to jar;
                // lifecycle listeners and security config must be re-implemented in Spring Boot.
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    if (hasFileName(doc.getSourcePath(), "weblogic-application.xml")) {
                        boolean inTargetModule = rootIsTarget ||
                                targetModuleDirs.stream().anyMatch(doc.getSourcePath()::startsWith);
                        return inTargetModule ? null : doc;
                    }
                }
                return super.visit(tree, ctx);
            }

            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                Xml.Document d = super.visitDocument(document, ctx);
                if (!hasFileName(d.getSourcePath(), "pom.xml")) return d;

                boolean isRoot   = d.getSourcePath().equals(rootPomPath);
                boolean isTarget = targetPomPaths.contains(d.getSourcePath());

                if (isRoot) {
                    // Define the Spring Boot version once as a property; BOM and plugin reference it.
                    doAfterVisit(new RawPropertyAdder(VERSION_PROPERTY, springBootVersion));
                    doAfterVisit(new RawManagedDependencyAdder(VERSION_PROP_REF));
                    // Remove maven-ear-plugin from pluginManagement — no EAR module remains
                    // after the packaging change, so the managed plugin entry becomes obsolete.
                    doAfterVisit(new RemoveMavenPluginVisitor(
                            "/project/build/pluginManagement/plugins",
                            EAR_PLUGIN_GROUP_ID, EAR_PLUGIN_ARTIFACT_ID));
                }
                if (isRoot && multiModule) {
                    doAfterVisit(new RawManagedPluginAdder(VERSION_PROP_REF));
                }
                if (isTarget) {
                    // Change packaging to jar — Spring Boot runs as a standalone executable jar.
                    // Only target modules (ear modules + standalone war modules) get this change;
                    // war-in-ear modules are excluded because they are not isTarget.
                    PomModule targetModule = acc.modules.get(d.getSourcePath());
                    if (targetModule != null &&
                            ("war".equals(targetModule.packaging) || "ear".equals(targetModule.packaging))) {
                        d.getRoot().getChild("packaging").ifPresent(packagingTag ->
                                doAfterVisit(new RemoveContentVisitor<ExecutionContext>(packagingTag, true, true)));
                    }

                    // Remove maven-ear-plugin from build/plugins — the module now builds as a
                    // Spring Boot jar and the EAR assembly configuration is no longer needed.
                    doAfterVisit(new RemoveMavenPluginVisitor(
                            "/project/build/plugins",
                            EAR_PLUGIN_GROUP_ID, EAR_PLUGIN_ARTIFACT_ID));
                    // Remove maven-war-plugin from target WAR modules — once packaging changes to
                    // jar, the WAR assembly configuration (web.xml, WEB-INF layout, etc.) no
                    // longer applies.
                    if (targetModule != null && "war".equals(targetModule.packaging)) {
                        doAfterVisit(new RemoveMavenPluginVisitor(
                                "/project/build/plugins",
                                WAR_PLUGIN_GROUP_ID, WAR_PLUGIN_ARTIFACT_ID));
                    }
                    // In multi-module projects versions are managed by the BOM in the root pom's
                    // dependencyManagement; in single-module the property reference is used.
                    String pluginVersion = multiModule ? null : VERSION_PROP_REF;
                    doAfterVisit(new RawPluginAdder(pluginVersion));
                    String depVersion = multiModule ? null : VERSION_PROP_REF;
                    doAfterVisit(new RawDependencyAdder(
                            "org.springframework.boot", "spring-boot-starter", depVersion, null));
                    doAfterVisit(new RawDependencyAdder(
                            "org.springframework.boot", "spring-boot-starter-test", depVersion, "test"));
                }
                return d;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Module resolution helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /**
     * Returns the root pom: the one with the fewest path components (shallowest depth).
     */
    static Path detectRootPomPath(Map<Path, PomModule> modules) {
        return modules.keySet().stream()
                .min(Comparator.comparingInt(Path::getNameCount))
                .orElse(null);
    }

    /**
     * Computes the set of target pom paths:
     * <ul>
     *   <li>Single-module: the single pom itself.</li>
     *   <li>Multi-module: all ear poms + war poms whose {@code groupId:artifactId}
     *       does NOT appear in any ear module's {@code <dependencies>}.</li>
     * </ul>
     */
    static Set<Path> resolveTargetPomPaths(Map<Path, PomModule> modules, boolean multiModule) {
        if (!multiModule) {
            return new LinkedHashSet<>(modules.keySet());
        }

        Set<String> warInEarCoords = modules.values().stream()
                .filter(m -> "ear".equals(m.packaging))
                .flatMap(m -> m.depCoords.stream())
                .collect(Collectors.toSet());

        return modules.values().stream()
                .filter(m -> "ear".equals(m.packaging) ||
                        ("war".equals(m.packaging)
                                && !warInEarCoords.contains(m.groupId + ":" + m.artifactId)))
                .map(m -> m.pomPath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // -------------------------------------------------------------------------
    // Raw XML dependency adder (avoids Maven POM download side effects)
    // -------------------------------------------------------------------------

    /**
     * Adds a {@code <dependency>} element directly to {@code /project/dependencies},
     * creating the {@code <dependencies>} section if it is absent.
     * Unlike {@link org.openrewrite.maven.AddDependencyVisitor}, this visitor does
     * not trigger Maven model re-resolution and therefore produces no download-error
     * markers in the output.
     */
    private static class RawDependencyAdder extends XmlIsoVisitor<ExecutionContext> {
        private static final XPathMatcher PROJECT_DEPS = new XPathMatcher("/project/dependencies");

        private final String groupId;
        private final String artifactId;
        @org.jspecify.annotations.Nullable
        private final String version;
        @org.jspecify.annotations.Nullable
        private final String scope;

        RawDependencyAdder(String groupId, String artifactId,
                           @org.jspecify.annotations.Nullable String version,
                           @org.jspecify.annotations.Nullable String scope) {
            this.groupId    = groupId;
            this.artifactId = artifactId;
            this.version    = version;
            this.scope      = scope;
        }

        @Override
        public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
            Xml.Document d = super.visitDocument(document, ctx);
            Xml.Tag root = d.getRoot();

            // Skip if already present
            if (root.getChild("dependencies")
                    .map(deps -> deps.getChildren("dependency").stream()
                            .anyMatch(dep -> groupId.equals(dep.getChildValue("groupId").orElse(""))
                                    && artifactId.equals(dep.getChildValue("artifactId").orElse(""))))
                    .orElse(false)) {
                return d;
            }

            // Create <dependencies/> if absent, using MavenTagInsertionComparator
            // to place it in the canonical Maven pom element order (before <build>)
            if (!root.getChild("dependencies").isPresent()) {
                doAfterVisit(new AddToTagVisitor<>(root, Xml.Tag.build("<dependencies/>"),
                        new MavenTagInsertionComparator(
                                root.getContent() == null
                                        ? Collections.emptyList()
                                        : root.getContent())));
            }

            // Insert the <dependency> into <dependencies>
            doAfterVisit(new InsertDependency());
            return d;
        }

        private class InsertDependency extends XmlIsoVisitor<ExecutionContext> {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = super.visitTag(tag, ctx);
                if (!PROJECT_DEPS.matches(getCursor())) return t;
                if (t.getChildren("dependency").stream()
                        .anyMatch(d -> groupId.equals(d.getChildValue("groupId").orElse(""))
                                && artifactId.equals(d.getChildValue("artifactId").orElse("")))) {
                    return t;
                }
                Indent indent = detectIndent(t, "dependency");
                // Build without leading whitespace — Xml.Tag.build() drops leading whitespace
                // since XML parsers treat it as insignificant. Set the prefix separately.
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
        }
    }

    // -------------------------------------------------------------------------
    // Indentation helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /** Prefix pair for a tag and its children. */
    static class Indent {
        final String tag;
        final String child;
        Indent(String tag, String child) { this.tag = tag; this.child = child; }
    }

    /**
     * Detects indentation from existing siblings named {@code siblingName}.
     * Falls back to the parent tag's own indent + detected unit when no siblings exist.
     */
    static Indent detectIndent(Xml.Tag parent, String siblingName) {
        List<Xml.Tag> siblings = parent.getChildren(siblingName);
        if (!siblings.isEmpty()) {
            Xml.Tag first = siblings.get(0);
            String tagPfx = first.getPrefix();
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

    private static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
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

    // -------------------------------------------------------------------------
    // Property adder
    // -------------------------------------------------------------------------

    /**
     * Adds a {@code <propertyName>value</propertyName>} entry to {@code /project/properties},
     * creating the section if absent and placing it before {@code <dependencyManagement>}.
     */
    private static class RawPropertyAdder extends XmlIsoVisitor<ExecutionContext> {
        private static final XPathMatcher PROPERTIES_PATH = new XPathMatcher("/project/properties");
        private static final XPathMatcher PROJECT_PATH    = new XPathMatcher("/project");

        private final String propertyName;
        private final String propertyValue;

        RawPropertyAdder(String propertyName, String propertyValue) {
            this.propertyName  = propertyName;
            this.propertyValue = propertyValue;
        }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            Xml.Tag t = super.visitTag(tag, ctx);

            if (PROPERTIES_PATH.matches(getCursor())) {
                if (t.getChild(propertyName).isPresent()) return t;
                Indent indent = detectIndent(t, propertyName);
                return appendChild(t, Xml.Tag.build(
                        "<" + propertyName + ">" + propertyValue + "</" + propertyName + ">")
                        .withPrefix(indent.tag));
            }

            if (PROJECT_PATH.matches(getCursor()) && !t.getChild("properties").isPresent()) {
                String u  = detectUnit(t);
                String l1 = "\n" + u;
                String l2 = "\n" + rep(u, 2);
                Xml.Tag newProps = Xml.Tag.build(
                        "<properties>" + l2 +
                        "<" + propertyName + ">" + propertyValue + "</" + propertyName + ">" +
                        l1 + "</properties>")
                        .withPrefix(l1);
                return insertBefore(t, newProps, "dependencyManagement", "dependencies", "build");
            }

            return t;
        }
    }

    // -------------------------------------------------------------------------
    // Plugin inserters (indentation-aware, replace AddPluginVisitor / AddManagedPlugin)
    // -------------------------------------------------------------------------

    /**
     * Inserts {@code spring-boot-maven-plugin} into {@code <build><plugins>},
     * creating missing sections if needed.
     * Uses {@link Xml.Tag#withContent} directly to bypass AddToTagVisitor's autoFormat,
     * which would otherwise override the indentation we detect from existing siblings.
     */
    private static class RawPluginAdder extends XmlIsoVisitor<ExecutionContext> {
        private static final XPathMatcher PLUGINS_PATH = new XPathMatcher("/project/build/plugins");
        private static final XPathMatcher BUILD_PATH   = new XPathMatcher("/project/build");
        private static final XPathMatcher PROJECT_PATH = new XPathMatcher("/project");

        @Nullable private final String version;

        RawPluginAdder(@Nullable String version) { this.version = version; }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            Xml.Tag t = super.visitTag(tag, ctx);

            if (PLUGINS_PATH.matches(getCursor())) {
                if (pluginPresent(t)) return t;
                Indent indent = detectIndent(t, "plugin");
                Xml.Tag newPlugin = buildPluginTag(indent);
                if (t.getChildren("plugin").isEmpty()) {
                    // <plugins> exists but all plugins were removed; only stale CharData whitespace
                    // nodes remain. Replacing the content list avoids blank lines in the output.
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    List newContent = new ArrayList(Collections.singletonList(newPlugin));
                    return t.withContent(newContent);
                }
                return appendChild(t, newPlugin);
            }

            if (BUILD_PATH.matches(getCursor()) && !t.getChild("plugins").isPresent()) {
                String bi = t.getPrefix().replaceAll("^[\\r\\n]+", "");
                String u  = detectUnit(t);
                String l2 = "\n" + bi + u;
                String l3 = "\n" + bi + u + u;
                String l4 = "\n" + bi + u + u + u;
                Xml.Tag newPlugins = Xml.Tag.build(
                        "<plugins>" + pluginXmlWithPrefix(new Indent(l3, l4)) + l2 + "</plugins>")
                        .withPrefix(l2);
                return appendChild(t, newPlugins);
            }

            if (PROJECT_PATH.matches(getCursor()) && !t.getChild("build").isPresent()) {
                String u  = detectUnit(t);
                String l1 = "\n" + u;
                String l2 = "\n" + rep(u, 2);
                String l3 = "\n" + rep(u, 3);
                String l4 = "\n" + rep(u, 4);
                Xml.Tag newBuild = Xml.Tag.build(
                        "<build>" + l2 + "<plugins>" + pluginXmlWithPrefix(new Indent(l3, l4)) +
                        l2 + "</plugins>" + l1 + "</build>")
                        .withPrefix(l1);
                return appendChild(t, newBuild);
            }

            return t;
        }

        private boolean pluginPresent(Xml.Tag pluginsTag) {
            return pluginsTag.getChildren("plugin").stream()
                    .anyMatch(p -> PLUGIN_GROUP_ID.equals(p.getChildValue("groupId").orElse(""))
                            && PLUGIN_ARTIFACT_ID.equals(p.getChildValue("artifactId").orElse("")));
        }

        /**
         * Builds a {@code <plugin>} XML string WITH the leading prefix included.
         * Used when embedding the plugin inside a larger XML string (e.g. {@code <plugins>...}).
         */
        private String pluginXmlWithPrefix(Indent indent) {
            StringBuilder xml = new StringBuilder();
            xml.append(indent.tag).append("<plugin>")
               .append(indent.child).append("<groupId>").append(PLUGIN_GROUP_ID).append("</groupId>")
               .append(indent.child).append("<artifactId>").append(PLUGIN_ARTIFACT_ID).append("</artifactId>");
            if (version != null) {
                xml.append(indent.child).append("<version>").append(version).append("</version>");
            }
            xml.append(indent.tag).append("</plugin>");
            return xml.toString();
        }

        /**
         * Builds a standalone {@code <plugin>} Xml.Tag with the prefix set via
         * {@link Xml.Tag#withPrefix} (since {@link Xml.Tag#build} drops leading whitespace).
         */
        private Xml.Tag buildPluginTag(Indent indent) {
            StringBuilder xml = new StringBuilder("<plugin>");
            xml.append(indent.child).append("<groupId>").append(PLUGIN_GROUP_ID).append("</groupId>")
               .append(indent.child).append("<artifactId>").append(PLUGIN_ARTIFACT_ID).append("</artifactId>");
            if (version != null) {
                xml.append(indent.child).append("<version>").append(version).append("</version>");
            }
            xml.append(indent.tag).append("</plugin>");
            return Xml.Tag.build(xml.toString()).withPrefix(indent.tag);
        }
    }

    /**
     * Inserts {@code spring-boot-maven-plugin} into {@code <build><pluginManagement><plugins>},
     * creating missing sections if needed.
     * Uses {@link Xml.Tag#withContent} to bypass AddToTagVisitor's autoFormat.
     */
    private static class RawManagedPluginAdder extends XmlIsoVisitor<ExecutionContext> {
        private static final XPathMatcher PM_PLUGINS_PATH = new XPathMatcher("/project/build/pluginManagement/plugins");
        private static final XPathMatcher PM_PATH         = new XPathMatcher("/project/build/pluginManagement");
        private static final XPathMatcher BUILD_PATH      = new XPathMatcher("/project/build");
        private static final XPathMatcher PROJECT_PATH    = new XPathMatcher("/project");

        private final String version;

        RawManagedPluginAdder(String version) { this.version = version; }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            Xml.Tag t = super.visitTag(tag, ctx);

            if (PM_PLUGINS_PATH.matches(getCursor())) {
                if (pluginPresent(t)) return t;
                Indent indent = detectIndent(t, "plugin");
                Xml.Tag newPlugin = buildPluginTag(indent);
                if (t.getChildren("plugin").isEmpty()) {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    List newContent = new ArrayList(Collections.singletonList(newPlugin));
                    return t.withContent(newContent);
                }
                return appendChild(t, newPlugin);
            }

            if (PM_PATH.matches(getCursor()) && !t.getChild("plugins").isPresent()) {
                String pi = t.getPrefix().replaceAll("^[\\r\\n]+", "");
                String u  = detectUnit(t);
                String l2 = "\n" + pi + u;
                String l3 = "\n" + pi + u + u;
                String l4 = "\n" + pi + u + u + u;
                Xml.Tag newPlugins = Xml.Tag.build(
                        "<plugins>" + pluginXmlWithPrefix(new Indent(l3, l4)) + l2 + "</plugins>")
                        .withPrefix(l2);
                return appendChild(t, newPlugins);
            }

            if (BUILD_PATH.matches(getCursor()) && !t.getChild("pluginManagement").isPresent()) {
                String bi = t.getPrefix().replaceAll("^[\\r\\n]+", "");
                String u  = detectUnit(t);
                String l2 = "\n" + bi + u;
                String l3 = "\n" + bi + u + u;
                String l4 = "\n" + bi + u + u + u;
                String l5 = "\n" + bi + u + u + u + u;
                Xml.Tag newPm = Xml.Tag.build(
                        "<pluginManagement>" + l3 + "<plugins>" +
                        pluginXmlWithPrefix(new Indent(l4, l5)) +
                        l3 + "</plugins>" + l2 + "</pluginManagement>")
                        .withPrefix(l2);
                return appendChild(t, newPm);
            }

            if (PROJECT_PATH.matches(getCursor()) && !t.getChild("build").isPresent()) {
                String u  = detectUnit(t);
                String l1 = "\n" + u;
                String l2 = "\n" + rep(u, 2);
                String l3 = "\n" + rep(u, 3);
                String l4 = "\n" + rep(u, 4);
                String l5 = "\n" + rep(u, 5);
                Xml.Tag newBuild = Xml.Tag.build(
                        "<build>" + l2 + "<pluginManagement>" + l3 + "<plugins>" +
                        pluginXmlWithPrefix(new Indent(l4, l5)) +
                        l3 + "</plugins>" + l2 + "</pluginManagement>" + l1 + "</build>")
                        .withPrefix(l1);
                return appendChild(t, newBuild);
            }

            return t;
        }

        private boolean pluginPresent(Xml.Tag pluginsTag) {
            return pluginsTag.getChildren("plugin").stream()
                    .anyMatch(p -> PLUGIN_GROUP_ID.equals(p.getChildValue("groupId").orElse(""))
                            && PLUGIN_ARTIFACT_ID.equals(p.getChildValue("artifactId").orElse("")));
        }

        private String pluginXmlWithPrefix(Indent indent) {
            return indent.tag + "<plugin>" +
                    indent.child + "<groupId>" + PLUGIN_GROUP_ID + "</groupId>" +
                    indent.child + "<artifactId>" + PLUGIN_ARTIFACT_ID + "</artifactId>" +
                    indent.child + "<version>" + version + "</version>" +
                    indent.tag + "</plugin>";
        }

        private Xml.Tag buildPluginTag(Indent indent) {
            return Xml.Tag.build("<plugin>" +
                    indent.child + "<groupId>" + PLUGIN_GROUP_ID + "</groupId>" +
                    indent.child + "<artifactId>" + PLUGIN_ARTIFACT_ID + "</artifactId>" +
                    indent.child + "<version>" + version + "</version>" +
                    indent.tag + "</plugin>").withPrefix(indent.tag);
        }
    }

    // -------------------------------------------------------------------------
    // EAR plugin remover
    // -------------------------------------------------------------------------

    /**
     * Removes all {@code <plugin>} entries matching the given groupId + artifactId from the
     * {@code <plugins>} section identified by {@code pluginsXPath}.
     * Used to remove {@code maven-ear-plugin} from target module {@code <build><plugins>}
     * and from the root pom's {@code <build><pluginManagement><plugins>}.
     */
    private static class RemoveMavenPluginVisitor extends XmlIsoVisitor<ExecutionContext> {
        private final XPathMatcher pluginsMatcher;
        private final String groupId;
        private final String artifactId;

        RemoveMavenPluginVisitor(String pluginsXPath, String groupId, String artifactId) {
            this.pluginsMatcher = new XPathMatcher(pluginsXPath);
            this.groupId    = groupId;
            this.artifactId = artifactId;
        }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            Xml.Tag t = super.visitTag(tag, ctx);
            if (!pluginsMatcher.matches(getCursor())) return t;
            t.getChildren("plugin").stream()
                    .filter(p -> groupId.equals(p.getChildValue("groupId").orElse(""))
                            && artifactId.equals(p.getChildValue("artifactId").orElse("")))
                    .forEach(p -> doAfterVisit(new RemoveContentVisitor<>(p, false, true)));
            return t;
        }
    }

    // -------------------------------------------------------------------------
    // BOM inserter (indentation-aware, replaces AddManagedDependency in YAML)
    // -------------------------------------------------------------------------

    /**
     * Inserts the Spring Boot BOM into {@code <dependencyManagement><dependencies>} of the
     * root pom, creating missing sections if needed.
     * Uses {@link Xml.Tag#withContent} to bypass AddToTagVisitor's autoFormat.
     */
    private static class RawManagedDependencyAdder extends XmlIsoVisitor<ExecutionContext> {
        private static final XPathMatcher DM_DEPS_PATH = new XPathMatcher("/project/dependencyManagement/dependencies");
        private static final XPathMatcher DM_PATH      = new XPathMatcher("/project/dependencyManagement");
        private static final XPathMatcher PROJECT_PATH  = new XPathMatcher("/project");

        private static final String BOM_GROUP    = "org.springframework.boot";
        private static final String BOM_ARTIFACT = "spring-boot-dependencies";

        private final String springBootVersion;

        RawManagedDependencyAdder(String springBootVersion) {
            this.springBootVersion = springBootVersion;
        }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            Xml.Tag t = super.visitTag(tag, ctx);

            if (DM_DEPS_PATH.matches(getCursor())) {
                if (bomPresent(t)) return t;
                Indent indent = detectIndent(t, "dependency");
                return appendChild(t, buildBomTag(indent));
            }

            if (DM_PATH.matches(getCursor()) && !t.getChild("dependencies").isPresent()) {
                String di = t.getPrefix().replaceAll("^[\\r\\n]+", "");
                String u  = detectUnit(t);
                String l2 = "\n" + di + u;
                String l3 = "\n" + di + u + u;
                String l4 = "\n" + di + u + u + u;
                Xml.Tag newDeps = Xml.Tag.build(
                        "<dependencies>" + bomXmlWithPrefix(new Indent(l3, l4)) + l2 + "</dependencies>")
                        .withPrefix(l2);
                return appendChild(t, newDeps);
            }

            if (PROJECT_PATH.matches(getCursor()) && !t.getChild("dependencyManagement").isPresent()) {
                String u  = detectUnit(t);
                String l1 = "\n" + u;
                String l2 = "\n" + rep(u, 2);
                String l3 = "\n" + rep(u, 3);
                String l4 = "\n" + rep(u, 4);
                Xml.Tag newDm = Xml.Tag.build(
                        "<dependencyManagement>" + l2 + "<dependencies>" +
                        bomXmlWithPrefix(new Indent(l3, l4)) +
                        l2 + "</dependencies>" + l1 + "</dependencyManagement>")
                        .withPrefix(l1);
                // Insert before <dependencies> or <build> to follow canonical Maven pom order
                return insertBefore(t, newDm, "dependencies", "build");
            }

            return t;
        }

        private boolean bomPresent(Xml.Tag depsTag) {
            return depsTag.getChildren("dependency").stream()
                    .anyMatch(d -> BOM_GROUP.equals(d.getChildValue("groupId").orElse(""))
                            && BOM_ARTIFACT.equals(d.getChildValue("artifactId").orElse("")));
        }

        private String bomXmlWithPrefix(Indent indent) {
            return indent.tag + "<dependency>" +
                    indent.child + "<groupId>" + BOM_GROUP + "</groupId>" +
                    indent.child + "<artifactId>" + BOM_ARTIFACT + "</artifactId>" +
                    indent.child + "<version>" + springBootVersion + "</version>" +
                    indent.child + "<type>pom</type>" +
                    indent.child + "<scope>import</scope>" +
                    indent.tag + "</dependency>";
        }

        private Xml.Tag buildBomTag(Indent indent) {
            return Xml.Tag.build("<dependency>" +
                    indent.child + "<groupId>" + BOM_GROUP + "</groupId>" +
                    indent.child + "<artifactId>" + BOM_ARTIFACT + "</artifactId>" +
                    indent.child + "<version>" + springBootVersion + "</version>" +
                    indent.child + "<type>pom</type>" +
                    indent.child + "<scope>import</scope>" +
                    indent.tag + "</dependency>").withPrefix(indent.tag);
        }
    }

    // -------------------------------------------------------------------------
    // Shared tree mutation helper
    // -------------------------------------------------------------------------

    /** Appends {@code newChild} at the end of {@code parent}'s content list. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Xml.Tag appendChild(Xml.Tag parent, Xml.Tag newChild) {
        List newContent = parent.getContent() != null
                ? new ArrayList(parent.getContent())
                : new ArrayList();
        newContent.add(newChild);
        return parent.withContent(newContent);
    }

    /**
     * Inserts {@code newChild} before the first child tag whose name appears in
     * {@code beforeTagNames}, or appends it at the end if none are found.
     * Preserves all other content items (including whitespace nodes) in their
     * original order.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Xml.Tag insertBefore(Xml.Tag parent, Xml.Tag newChild, String... beforeTagNames) {
        Set<String> stopAt = new HashSet<>(Arrays.asList(beforeTagNames));
        List existingContent = parent.getContent() != null ? parent.getContent() : Collections.emptyList();
        List newContent = new ArrayList();
        boolean inserted = false;
        for (Object item : existingContent) {
            if (!inserted && item instanceof Xml.Tag && stopAt.contains(((Xml.Tag) item).getName())) {
                newContent.add(newChild);
                inserted = true;
            }
            newContent.add(item);
        }
        if (!inserted) {
            newContent.add(newChild);
        }
        return parent.withContent(newContent);
    }
}
