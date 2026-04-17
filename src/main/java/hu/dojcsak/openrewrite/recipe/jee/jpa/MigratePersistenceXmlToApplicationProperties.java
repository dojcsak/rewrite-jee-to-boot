package hu.dojcsak.openrewrite.recipe.jee.jpa;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads properties from {@code persistence.xml}, maps them to their Spring Boot equivalents
 * in {@code application.properties}, and deletes the {@code persistence.xml} file.
 * <p>
 * Properties whose JPA/Hibernate key has no Spring Boot equivalent (e.g. JNDI data sources,
 * cache providers) are silently skipped — they require manual intervention.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigratePersistenceXmlToApplicationProperties
        extends ScanningRecipe<MigratePersistenceXmlToApplicationProperties.Accumulator> {

    /** Maps persistence.xml property names to Spring Boot application.properties keys. */
    private static final Map<String, String> PROPERTY_MAP = new LinkedHashMap<>();
    static {
        PROPERTY_MAP.put("hibernate.hbm2ddl.auto",           "spring.jpa.hibernate.ddl-auto");
        PROPERTY_MAP.put("hibernate.dialect",                "spring.jpa.database-platform");
        PROPERTY_MAP.put("hibernate.connection.driver_class","spring.datasource.driver-class-name");
        PROPERTY_MAP.put("hibernate.connection.url",         "spring.datasource.url");
        PROPERTY_MAP.put("hibernate.connection.username",    "spring.datasource.username");
        PROPERTY_MAP.put("hibernate.connection.password",    "spring.datasource.password");
        PROPERTY_MAP.put("javax.persistence.jdbc.url",       "spring.datasource.url");
        PROPERTY_MAP.put("javax.persistence.jdbc.driver",    "spring.datasource.driver-class-name");
        PROPERTY_MAP.put("javax.persistence.jdbc.user",      "spring.datasource.username");
        PROPERTY_MAP.put("javax.persistence.jdbc.password",  "spring.datasource.password");
    }

    // -------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------

    public static class Accumulator {
        /** Spring Boot property key → value extracted from persistence.xml. */
        final Map<String, String> properties = new LinkedHashMap<>();
        /** True when at least one persistence.xml was found in the project. */
        boolean persistenceXmlFound;
        /** True when application.properties already exists in the project. */
        boolean applicationPropertiesFound;
    }

    // -------------------------------------------------------------------------
    // Recipe metadata
    // -------------------------------------------------------------------------

    @Override
    public String getDisplayName() {
        return "Migrate persistence.xml properties to application.properties";
    }

    @Override
    public String getDescription() {
        return "Reads JPA and Hibernate properties from META-INF/persistence.xml, maps them to " +
               "their Spring Boot application.properties equivalents, writes them to " +
               "application.properties (creating the file if necessary), and deletes persistence.xml. " +
               "Properties that have no automatic mapping (e.g. JNDI data sources) are skipped.";
    }

    // -------------------------------------------------------------------------
    // ScanningRecipe
    // -------------------------------------------------------------------------

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    /**
     * Visits every source file; for {@code persistence.xml} it extracts mapped properties,
     * for {@code application.properties} it records that the file already exists.
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    if (hasFileName(doc.getSourcePath(), "persistence.xml")) {
                        acc.persistenceXmlFound = true;
                        scanPersistenceXml(doc.getRoot(), acc);
                    }
                } else if (tree instanceof Properties.File) {
                    Properties.File props = (Properties.File) tree;
                    if (hasFileName(props.getSourcePath(), "application.properties")) {
                        acc.applicationPropertiesFound = true;
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Generates a new {@code application.properties} if none existed and properties were found.
     */
    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (!acc.persistenceXmlFound || acc.properties.isEmpty() || acc.applicationPropertiesFound) {
            return Collections.emptyList();
        }

        String content = acc.properties.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n", "", "\n"));

        return new PropertiesParser().parse(content)
                .map(sf -> (SourceFile) sf.withSourcePath(
                        Paths.get("src/main/resources/application.properties")))
                .collect(Collectors.toList());
    }

    /**
     * Deletes {@code persistence.xml} and – when {@code application.properties} already existed –
     * adds any missing Spring Boot properties to it.
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (!acc.persistenceXmlFound) {
            return TreeVisitor.noop();
        }
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    if (hasFileName(doc.getSourcePath(), "persistence.xml")) {
                        return null; // delete the file
                    }
                }
                if (!acc.properties.isEmpty() && acc.applicationPropertiesFound
                        && tree instanceof Properties.File) {
                    Properties.File props = (Properties.File) tree;
                    if (hasFileName(props.getSourcePath(), "application.properties")) {
                        return addMissingProperties(props, acc.properties);
                    }
                }
                return tree;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void scanPersistenceXml(Xml.Tag root, Accumulator acc) {
        for (Xml.Tag persistenceUnit : childTags(root, "persistence-unit")) {
            for (Xml.Tag propertiesTag : childTags(persistenceUnit, "properties")) {
                for (Xml.Tag property : childTags(propertiesTag, "property")) {
                    String name  = attr(property, "name");
                    String value = attr(property, "value");
                    if (name != null && value != null) {
                        String springKey = PROPERTY_MAP.get(name);
                        if (springKey != null) {
                            acc.properties.putIfAbsent(springKey, value);
                        }
                    }
                }
            }
        }
    }

    private static Properties.File addMissingProperties(
            Properties.File file, Map<String, String> springProperties) {

        Set<String> existing = file.getContent().stream()
                .filter(c -> c instanceof Properties.Entry)
                .map(c -> ((Properties.Entry) c).getKey())
                .collect(Collectors.toSet());

        List<Properties.Entry> toAdd = new ArrayList<>();
        for (Map.Entry<String, String> entry : springProperties.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                toAdd.add(new Properties.Entry(
                        Tree.randomId(),
                        "\n",
                        Markers.EMPTY,
                        entry.getKey(),
                        "",
                        Properties.Entry.Delimiter.EQUALS,
                        new Properties.Value(
                                Tree.randomId(),
                                "",
                                Markers.EMPTY,
                                entry.getValue())));
            }
        }

        if (toAdd.isEmpty()) {
            return file; // nothing to add — avoid spurious change
        }

        List<Properties.Content> content = new ArrayList<>(file.getContent());
        content.addAll(toAdd);
        return file.withContent(content);
    }

    /** Returns child tags of {@code parent} with the given tag name. */
    private static List<Xml.Tag> childTags(Xml.Tag parent, String name) {
        if (parent.getContent() == null) return Collections.emptyList();
        return parent.getContent().stream()
                .filter(c -> c instanceof Xml.Tag && name.equals(((Xml.Tag) c).getName()))
                .map(c -> (Xml.Tag) c)
                .collect(Collectors.toList());
    }

    /** Returns the value of the named attribute, or {@code null} if absent. */
    private static String attr(Xml.Tag tag, String name) {
        return tag.getAttributes().stream()
                .filter(a -> name.equals(a.getKey().getName()))
                .map(a -> a.getValue().getValue())
                .findFirst()
                .orElse(null);
    }

    /** Returns {@code true} when the path's filename (last segment) matches the given name. */
    private static boolean hasFileName(java.nio.file.Path path, String name) {
        java.nio.file.Path fileName = path.getFileName();
        return fileName != null && name.equals(fileName.toString());
    }
}
