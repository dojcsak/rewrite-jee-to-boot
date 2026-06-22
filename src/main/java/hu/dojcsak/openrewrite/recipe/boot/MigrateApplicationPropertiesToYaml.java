package hu.dojcsak.openrewrite.recipe.boot;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.util.*;

/**
 * Converts {@code application*.properties} (e.g. {@code application.properties},
 * {@code application-dev.properties}) into the equivalent nested {@code application*.yaml},
 * deleting the original {@code .properties} file once the conversion succeeds.
 * <p>
 * Dotted property keys are nested into a YAML mapping tree, preserving the original key order.
 * Comment lines immediately preceding a property are carried over as YAML comments above the
 * corresponding key; {@code !}-delimited comments are normalized to {@code #}, since YAML only
 * recognizes the latter. A key conflict such as {@code a=1} together with {@code a.b=2} is
 * resolved last-one-wins with no validation; such conflicts are rare and require manual review
 * regardless of representation.
 * <p>
 * When a {@code .yml}/{@code .yaml} file with the target name already exists, the conversion for
 * that file is skipped entirely (the {@code .properties} file is left untouched) to avoid
 * clobbering hand-written YAML.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateApplicationPropertiesToYaml
        extends ScanningRecipe<MigrateApplicationPropertiesToYaml.Accumulator> {

    // -------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------

    /** A single {@code key=value} property entry, with any comment lines that preceded it. */
    private static final class PropertyEntry {
        final String key;
        final String value;
        final List<String> comments;

        PropertyEntry(String key, String value, List<String> comments) {
            this.key = key;
            this.value = value;
            this.comments = comments;
        }
    }

    /** A leaf value in the nested YAML tree, carrying the comments that preceded it. */
    private static final class Leaf {
        final String value;
        final List<String> comments;

        Leaf(String value, List<String> comments) {
            this.value = value;
            this.comments = comments;
        }
    }

    public static class Accumulator {
        /** Source path of each application*.properties file → its ordered property entries. */
        final Map<Path, List<PropertyEntry>> propertiesFiles = new LinkedHashMap<>();
        /** Sibling .yml/.yaml target paths that already exist in the project. */
        final Set<Path> existingYamlPaths = new HashSet<>();
    }

    // -------------------------------------------------------------------------
    // Recipe metadata
    // -------------------------------------------------------------------------

    @Override
    public String getDisplayName() {
        return "Migrate application.properties to application.yaml";
    }

    @Override
    public String getDescription() {
        return "Converts application*.properties (e.g. application.properties, " +
               "application-dev.properties) into the equivalent nested application*.yaml, " +
               "preserving key order, and deletes the original .properties file. " +
               "Comment lines preceding a property are carried over as YAML comments above the " +
               "corresponding key. Skips any .properties file whose target .yml/.yaml already " +
               "exists, to avoid clobbering hand-written YAML.";
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
                if (tree instanceof Properties.File) {
                    Properties.File props = (Properties.File) tree;
                    if (isApplicationProperties(props.getSourcePath())) {
                        acc.propertiesFiles.put(props.getSourcePath(), entriesOf(props));
                    }
                } else if (tree instanceof Yaml.Documents) {
                    acc.existingYamlPaths.add(((Yaml.Documents) tree).getSourcePath());
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generated = new ArrayList<>();
        for (Map.Entry<Path, List<PropertyEntry>> e : acc.propertiesFiles.entrySet()) {
            Path yamlPath = yamlPathFor(e.getKey());
            if (acc.existingYamlPaths.contains(yamlPath)) {
                continue; // target already exists — leave both files for manual handling
            }
            String yamlText = toYaml(e.getValue());
            new YamlParser().parse(yamlText)
                    .map(sf -> (SourceFile) sf.withSourcePath(yamlPath))
                    .forEach(generated::add);
        }
        return generated;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.propertiesFiles.isEmpty()) {
            return TreeVisitor.noop();
        }
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof Properties.File) {
                    Properties.File props = (Properties.File) tree;
                    Path path = props.getSourcePath();
                    if (acc.propertiesFiles.containsKey(path)
                            && !acc.existingYamlPaths.contains(yamlPathFor(path))) {
                        return null; // delete — converted to YAML
                    }
                }
                return tree;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isApplicationProperties(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.startsWith("application") && name.endsWith(".properties");
    }

    private static Path yamlPathFor(Path propertiesPath) {
        String name = propertiesPath.getFileName().toString();
        String yamlName = name.substring(0, name.length() - ".properties".length()) + ".yaml";
        Path parent = propertiesPath.getParent();
        return parent != null ? parent.resolve(yamlName) : java.nio.file.Paths.get(yamlName);
    }

    /**
     * Walks the properties file's content in order, associating each entry with the contiguous
     * run of comment lines that directly precede it. A comment block at the end of the file with
     * no following entry is discarded, since there is nothing to attach it to.
     */
    private static List<PropertyEntry> entriesOf(Properties.File props) {
        List<PropertyEntry> result = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();
        for (Properties.Content content : props.getContent()) {
            if (content instanceof Properties.Comment) {
                pendingComments.add(((Properties.Comment) content).getMessage().trim());
            } else if (content instanceof Properties.Entry) {
                Properties.Entry entry = (Properties.Entry) content;
                result.add(new PropertyEntry(
                        entry.getKey(), entry.getValue().getText(), pendingComments));
                pendingComments = new ArrayList<>();
            }
        }
        return result;
    }

    /**
     * Builds a nested mapping tree from dotted property keys (preserving insertion order via
     * {@code LinkedHashMap}), then serializes it to YAML text using 2-space indentation.
     */
    @SuppressWarnings("unchecked")
    private static String toYaml(List<PropertyEntry> entries) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (PropertyEntry entry : entries) {
            String[] segments = entry.key.split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < segments.length - 1; i++) {
                Object next = node.get(segments[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    node.put(segments[i], next);
                }
                node = (Map<String, Object>) next;
            }
            node.put(segments[segments.length - 1], new Leaf(entry.value, entry.comments));
        }

        StringBuilder sb = new StringBuilder();
        writeYaml(root, 0, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeYaml(Map<String, Object> node, int depth, StringBuilder sb) {
        String indent = repeat("  ", depth);
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                sb.append(indent).append(entry.getKey()).append(":\n");
                writeYaml((Map<String, Object>) value, depth + 1, sb);
            } else {
                Leaf leaf = (Leaf) value;
                for (String comment : leaf.comments) {
                    sb.append(indent).append('#');
                    if (!comment.isEmpty()) {
                        sb.append(' ').append(comment);
                    }
                    sb.append('\n');
                }
                sb.append(indent).append(entry.getKey()).append(": ").append(leaf.value)
                        .append('\n');
            }
        }
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
