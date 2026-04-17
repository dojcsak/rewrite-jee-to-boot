package hu.dojcsak.openrewrite.recipe.boot;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates the three files required to bootstrap a Spring Boot application:
 * <ul>
 *   <li>{@code SpringBootApp.java} — main class annotated with {@code @SpringBootApplication}</li>
 *   <li>{@code SpringBootAppTest.java} — context-loads smoke test</li>
 *   <li>{@code application.properties} — empty placeholder</li>
 * </ul>
 *
 * <p>Each file is only generated when it does not already exist in the project.
 * The package is inferred from the longest common prefix of all packages found
 * under {@code src/main/java/}.</p>
 *
 * <p><b>Single-module:</b> files are generated at project root.<br>
 * <b>Multi-module:</b> files go into ear modules and war modules that are
 * <em>not</em> listed as a dependency in any ear module.</p>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddSpringBootApplicationFiles
        extends ScanningRecipe<AddSpringBootApplicationFiles.Accumulator> {

    // -------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------

    public static class Accumulator {
        /** All package names found in src/main/java source files. */
        final Set<String> mainPackages = new LinkedHashSet<>();
        /** True when any class annotated with {@code @SpringBootApplication} was found. */
        boolean springBootApplicationFound;
        /** True when any class annotated with {@code @SpringBootTest} was found. */
        boolean springBootTestFound;
        /** True when {@code application.properties} already exists. */
        boolean applicationPropertiesFound;
        /** Module info extracted from every pom.xml found in the project. */
        final Map<Path, ConfigureSpringBootMaven.PomModule> pomModules = new LinkedHashMap<>();
    }

    // -------------------------------------------------------------------------
    // Recipe metadata
    // -------------------------------------------------------------------------

    @Override
    public String getDisplayName() {
        return "Add Spring Boot application bootstrap files";
    }

    @Override
    public String getDescription() {
        return "Generates SpringBootApp.java (main class), SpringBootAppTest.java (context-loads test), " +
               "and an empty application.properties when these files are absent. " +
               "In multi-module projects the files are placed inside ear modules and war modules " +
               "that are not listed as a dependency in any ear module. " +
               "The package is derived from the longest common prefix of all packages found under src/main/java/.";
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
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    scanJava((J.CompilationUnit) tree, acc);
                } else if (tree instanceof Properties.File) {
                    scanProperties((Properties.File) tree, acc);
                } else if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    if (hasFileName(doc.getSourcePath(), "pom.xml")) {
                        // Use Maven resolution result when available so that property
                        // expressions like ${project.groupId} or ${application.id} are
                        // resolved before comparing war/ear coordinates.
                        MavenResolutionResult mavenResult = doc.getMarkers()
                                .findFirst(MavenResolutionResult.class).orElse(null);
                        if (mavenResult != null) {
                            scanPomWithResolution(doc, mavenResult.getPom(), acc);
                        } else {
                            scanPom(doc, acc);
                        }
                    }
                }
                return tree;
            }
        };
    }

    private static void scanJava(J.CompilationUnit cu, Accumulator acc) {
        String path = cu.getSourcePath().toString().replace('\\', '/');
        if (path.contains("src/main/java/") && cu.getPackageDeclaration() != null) {
            String pkg = cu.getPackageDeclaration().getPackageName();
            if (!pkg.isEmpty()) acc.mainPackages.add(pkg);
        }
        for (J.ClassDeclaration classDecl : cu.getClasses()) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                String name = ann.getSimpleName();
                if ("SpringBootApplication".equals(name)) acc.springBootApplicationFound = true;
                if ("SpringBootTest".equals(name))        acc.springBootTestFound = true;
            }
        }
    }

    private static void scanProperties(Properties.File props, Accumulator acc) {
        if (hasFileName(props.getSourcePath(), "application.properties")) {
            acc.applicationPropertiesFound = true;
        }
    }

    /**
     * Pom scanning using the already-resolved Maven model.
     * Property expressions (e.g. {@code ${project.groupId}}, {@code ${application.id}})
     * are resolved via {@link ResolvedPom#getValue(String)}, avoiding the mismatch that
     * occurs when raw XML values are compared.
     */
    private static void scanPomWithResolution(Xml.Document doc, ResolvedPom resolvedPom,
                                              Accumulator acc) {
        String groupId = resolvedPom.getGroupId();
        if (groupId == null || groupId.isEmpty()) {
            groupId = resolvedPom.getValue("${project.parent.groupId}");
        }
        if (groupId == null) groupId = "";

        String artifactId = resolvedPom.getArtifactId() != null ? resolvedPom.getArtifactId() : "";
        String packaging  = resolvedPom.getPackaging(); // never null; defaults to "jar"

        Set<String> depCoords = new LinkedHashSet<>();
        for (org.openrewrite.maven.tree.Dependency dep : resolvedPom.getRequestedDependencies()) {
            String g = resolvedPom.getValue(dep.getGroupId());
            String a = resolvedPom.getValue(dep.getArtifactId());
            if (g != null && a != null && !g.isEmpty() && !a.isEmpty()) {
                depCoords.add(g + ":" + a);
            }
        }

        acc.pomModules.put(doc.getSourcePath(),
                new ConfigureSpringBootMaven.PomModule(
                        doc.getSourcePath(), groupId, artifactId, packaging, depCoords));
    }

    private static void scanPom(Xml.Document doc, Accumulator acc) {
        if (!hasFileName(doc.getSourcePath(), "pom.xml")) return;
        Xml.Tag project = doc.getRoot();

        String groupId = project.getChildValue("groupId")
                .orElseGet(() -> project.getChild("parent")
                        .flatMap(p -> p.getChildValue("groupId"))
                        .orElse(""));
        String artifactId = project.getChildValue("artifactId").orElse("");
        String packaging  = project.getChildValue("packaging").orElse("jar");

        Set<String> depCoords = new LinkedHashSet<>();
        project.getChild("dependencies").ifPresent(deps ->
                deps.getChildren("dependency").forEach(dep -> {
                    String g = dep.getChildValue("groupId").orElse("");
                    String a = dep.getChildValue("artifactId").orElse("");
                    if (!g.isEmpty() && !a.isEmpty()) depCoords.add(g + ":" + a);
                }));

        acc.pomModules.put(doc.getSourcePath(),
                new ConfigureSpringBootMaven.PomModule(
                        doc.getSourcePath(), groupId, artifactId, packaging, depCoords));
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        String pkg     = detectPackage(acc.mainPackages);
        String pkgDecl = pkg.isEmpty() ? "" : "package " + pkg + ";\n\n";
        String pkgPath = pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/";
        String pkgTodo = pkg.isEmpty()
                ? "// TODO: Move this class into the appropriate package.\n"
                : "";

        List<Path> targetRoots = resolveTargetRoots(acc);
        List<SourceFile> generated = new ArrayList<>();

        for (Path moduleRoot : targetRoots) {
            String prefix = moduleRoot.toString().isEmpty()
                    ? ""
                    : moduleRoot.toString().replace('\\', '/') + "/";

            if (!acc.springBootApplicationFound) {
                String source = pkgDecl + pkgTodo +
                        "import org.springframework.boot.SpringApplication;\n" +
                        "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                        "@SpringBootApplication\n" +
                        "public class SpringBootApp {\n\n" +
                        "    public static void main(String[] args) {\n" +
                        "        SpringApplication.run(SpringBootApp.class, args);\n" +
                        "    }\n" +
                        "}\n";
                JavaParser.fromJavaVersion()
                        .classpathFromResources(ctx, "spring-boot-autoconfigure", "spring-boot")
                        .build()
                        .parse(source)
                        .map(sf -> (SourceFile) sf.withSourcePath(
                                Paths.get(prefix + "src/main/java/" + pkgPath + "SpringBootApp.java")))
                        .findFirst()
                        .ifPresent(generated::add);
            }

            if (!acc.springBootTestFound) {
                String source = pkgDecl + pkgTodo +
                        "import org.junit.jupiter.api.Test;\n" +
                        "import org.springframework.boot.test.context.SpringBootTest;\n\n" +
                        "@SpringBootTest\n" +
                        "class SpringBootAppTest {\n\n" +
                        "    @Test\n" +
                        "    void contextLoads() {\n" +
                        "    }\n" +
                        "}\n";
                JavaParser.fromJavaVersion()
                        .classpathFromResources(ctx, "spring-boot-test", "junit-jupiter-api")
                        .build()
                        .parse(source)
                        .map(sf -> (SourceFile) sf.withSourcePath(
                                Paths.get(prefix + "src/test/java/" + pkgPath + "SpringBootAppTest.java")))
                        .findFirst()
                        .ifPresent(generated::add);
            }

            if (!acc.applicationPropertiesFound) {
                new PropertiesParser().parse(System.lineSeparator())
                        .map(sf -> (SourceFile) sf.withSourcePath(
                                Paths.get(prefix + "src/main/resources/application.properties")))
                        .findFirst()
                        .ifPresent(generated::add);
            }
        }

        return generated;
    }

    /**
     * Determines where generated files should be placed.
     * <ul>
     *   <li>No pom.xml found → project root (test/simple scenario).</li>
     *   <li>Single module → project root.</li>
     *   <li>Multi-module → ear modules + war modules not in any ear's dependencies.</li>
     * </ul>
     */
    private static List<Path> resolveTargetRoots(Accumulator acc) {
        if (acc.pomModules.isEmpty()) {
            return Collections.singletonList(Paths.get(""));
        }

        boolean multiModule = acc.pomModules.size() > 1;
        if (!multiModule) {
            return Collections.singletonList(Paths.get(""));
        }

        Set<Path> targetPomPaths = ConfigureSpringBootMaven.resolveTargetPomPaths(
                acc.pomModules, true);

        if (targetPomPaths.isEmpty()) {
            return Collections.singletonList(Paths.get(""));
        }

        return targetPomPaths.stream()
                .map(p -> {
                    Path parent = p.getParent();
                    return parent != null ? parent : Paths.get("");
                })
                .collect(Collectors.toList());
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return TreeVisitor.noop();
    }

    // -------------------------------------------------------------------------
    // Package detection
    // -------------------------------------------------------------------------

    /**
     * Returns the longest common package prefix of all collected packages.
     * E.g. given {@code ["com.example.service", "com.example.repository"]} → {@code "com.example"}.
     */
    static String detectPackage(Set<String> packages) {
        if (packages.isEmpty()) return "";
        if (packages.size() == 1) return packages.iterator().next();
        List<String[]> split = packages.stream()
                .map(p -> p.split("\\."))
                .collect(Collectors.toList());
        String[] first = split.get(0);
        int commonLength = first.length;
        for (int i = 1; i < split.size(); i++) {
            String[] parts = split.get(i);
            int len = Math.min(commonLength, parts.length);
            int match = 0;
            while (match < len && first[match].equals(parts[match])) {
                match++;
            }
            commonLength = match;
        }
        if (commonLength == 0) return "";
        return String.join(".", Arrays.copyOf(first, commonLength));
    }

    static boolean hasFileName(Path path, String name) {
        Path fileName = path.getFileName();
        return fileName != null && name.equals(fileName.toString());
    }
}
