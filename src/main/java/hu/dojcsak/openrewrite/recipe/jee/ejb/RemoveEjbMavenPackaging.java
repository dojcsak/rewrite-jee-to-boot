package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes EJB-specific Maven packaging configuration:
 * <ul>
 *   <li>{@code <packaging>ejb</packaging>} from module POMs — {@code jar} is the Maven default,
 *       so removing the element is equivalent to setting it to {@code jar}.</li>
 *   <li>{@code <type>ejb</type>} from {@code <dependency>} declarations — after migration the
 *       artifact is a plain JAR, so the explicit type is no longer needed.</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveEjbMavenPackaging extends Recipe {

    String displayName = "Remove EJB Maven packaging configuration";

    String description = "Removes <packaging>ejb</packaging> from EJB module POMs " +
            "(jar is the Maven default, so the element can be omitted) " +
            "and <type>ejb</type> from dependency declarations that reference those modules.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                // Capture cursor context before super call moves through children
                boolean isProjectDep = isProjectDependencyTag(tag);

                Xml.Tag t = super.visitTag(tag, ctx);

                // Remove <packaging>ejb</packaging> from the root <project> element
                if ("project".equals(t.getName())) {
                    if (t.getChild("packaging")
                            .filter(p -> "ejb".equals(p.getValue().orElse("").trim()))
                            .isPresent()) {
                        t = removeChildTag(t, "packaging");
                    }
                }

                // Remove <type>ejb</type> from project/dependencyManagement <dependency> elements
                if (isProjectDep) {
                    if (t.getChild("type")
                            .filter(ty -> "ejb".equals(ty.getValue().orElse("").trim()))
                            .isPresent()) {
                        t = removeChildTag(t, "type");
                    }
                }

                return t;
            }

            // Returns true for <dependency> elements in <dependencies> sections that are NOT
            // inside <build><plugins><plugin> (i.e. project deps and dependencyManagement, not plugin deps).
            private boolean isProjectDependencyTag(Xml.Tag tag) {
                if (!"dependency".equals(tag.getName())) {
                    return false;
                }
                Cursor parentCursor = getCursor().getParent();
                if (!(parentCursor != null && parentCursor.getValue() instanceof Xml.Tag &&
                        "dependencies".equals(((Xml.Tag) parentCursor.getValue()).getName()))) {
                    return false;
                }
                Cursor grandparentCursor = parentCursor.getParent();
                if (grandparentCursor != null && grandparentCursor.getValue() instanceof Xml.Tag) {
                    return !"plugin".equals(((Xml.Tag) grandparentCursor.getValue()).getName());
                }
                return true;
            }

            private Xml.Tag removeChildTag(Xml.Tag parent, String childName) {
                List<? extends Content> content = parent.getContent();
                if (content == null) {
                    return parent;
                }
                List<Content> newContent = new ArrayList<>(content.size());
                for (Content c : content) {
                    if (!(c instanceof Xml.Tag && childName.equals(((Xml.Tag) c).getName()))) {
                        newContent.add(c);
                    }
                }
                return parent.withContent(newContent);
            }
        };
    }
}
