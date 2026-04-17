package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes {@code @javax.ejb.Remote} annotations from classes and interfaces,
 * adds a TODO comment (including the original annotation text) to flag them for
 * manual REST API migration, and logs a WARN message with the file path for each
 * affected component. These components expose distributed business logic that must
 * be manually replaced with a REST API (e.g. {@code @RestController}).
 */
@Slf4j
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkRemoteEjbs extends Recipe {

    @Override
    public String getDisplayName() {
        return "Remove @Remote EJB annotation and mark for manual REST migration";
    }

    @Override
    public String getDescription() {
        return "Removes @javax.ejb.Remote from classes and interfaces, adds a TODO comment " +
               "(including the original annotation text) to flag them for manual REST API migration, " +
               "and logs a WARN message with the file path for each affected component. " +
               "Remote EJB interfaces expose distributed business logic that cannot be automatically " +
               "migrated and should be replaced with a REST API (e.g. Spring @RestController).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final AnnotationMatcher remoteMatcher = new AnnotationMatcher("@javax.ejb.Remote");

            @Override
            public J.ClassDeclaration visitClassDeclaration(
                    J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (classDecl.getLeadingAnnotations().stream().noneMatch(remoteMatcher::matches)) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                // Capture annotation text before super() so getCursor() still points to classDecl.
                // Use reduce instead of findFirst to handle multiple @Remote occurrences.
                String remoteAnnotationText = classDecl.getLeadingAnnotations().stream()
                        .filter(remoteMatcher::matches)
                        .map(a -> normalizeAnnotationText(a.printTrimmed(getCursor())))
                        .reduce((a, b) -> a + " " + b)
                        .orElse("@Remote");

                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                String kind = cd.getKind() == J.ClassDeclaration.Kind.Type.Interface
                        ? "interface" : "class";
                log.warn("@Remote EJB {} '{}' in {} requires manual migration to REST API",
                        kind, cd.getSimpleName(), cu.getSourcePath());

                // Remove @Remote annotation(s)
                List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                annotations.removeIf(remoteMatcher::matches);
                cd = cd.withLeadingAnnotations(annotations);
                maybeRemoveImport("javax.ejb.Remote");

                // Strip the orphaned newline that separated @Remote from what follows it.
                // \R matches any line break: LF, CRLF, CR — handles both Unix and Windows files.
                if (cd.getLeadingAnnotations().isEmpty()) {
                    // No annotations remain: strip from the first modifier's prefix (e.g. "public"),
                    // or from the Kind prefix (e.g. "interface") when there are no modifiers either.
                    if (!cd.getModifiers().isEmpty()) {
                        J.Modifier first = cd.getModifiers().get(0);
                        String modWs = first.getPrefix().getWhitespace();
                        String stripped = modWs.replaceFirst("^\\R", "");
                        if (stripped.length() < modWs.length()) {
                            List<J.Modifier> newMods = new ArrayList<>(cd.getModifiers());
                            newMods.set(0, first.withPrefix(first.getPrefix().withWhitespace(stripped)));
                            cd = cd.withModifiers(newMods);
                        }
                    } else {
                        // No modifiers either: strip from the Kind node (interface/class keyword) prefix.
                        J.ClassDeclaration.Kind kindNode = cd.getPadding().getKind();
                        String kwWs = kindNode.getPrefix().getWhitespace();
                        String strippedKw = kwWs.replaceFirst("^\\R", "");
                        if (strippedKw.length() < kwWs.length()) {
                            cd = cd.getPadding().withKind(kindNode.withPrefix(kindNode.getPrefix().withWhitespace(strippedKw)));
                        }
                    }
                } else {
                    // Other annotations remain (e.g. @Remote was first, @Stateless was second):
                    // strip from the first remaining annotation's prefix to avoid a spurious blank line.
                    J.Annotation firstAnn = cd.getLeadingAnnotations().get(0);
                    String annWs = firstAnn.getPrefix().getWhitespace();
                    String stripped = annWs.replaceFirst("^\\R", "");
                    if (stripped.length() < annWs.length()) {
                        List<J.Annotation> newAnns = new ArrayList<>(cd.getLeadingAnnotations());
                        newAnns.set(0, firstAnn.withPrefix(firstAnn.getPrefix().withWhitespace(stripped)));
                        cd = cd.withLeadingAnnotations(newAnns);
                    }
                }

                // Add TODO comment to the class declaration prefix.
                // The suffix uses the file's native line ending + indentation extracted from the prefix,
                // so the next annotation or keyword appears on a new line at the correct indent level.
                Space prefix = cd.getPrefix();
                String indentation = extractIndentation(prefix.getWhitespace());
                String lineEnding = detectLineEnding(cd, prefix, cu);
                List<Comment> comments = new ArrayList<>(prefix.getComments());
                comments.add(new TextComment(false,
                        " TODO: " + remoteAnnotationText + " removed — expose as REST API (e.g. @RestController)",
                        lineEnding + indentation,
                        Markers.EMPTY));
                cd = cd.withPrefix(prefix.withComments(comments));

                return cd;
            }

            private String detectLineEnding(J.ClassDeclaration cd, Space prefix, J.CompilationUnit cu) {
                if (prefix.getWhitespace().contains("\r\n")) return "\r\n";
                for (J.Modifier mod : cd.getModifiers()) {
                    if (mod.getPrefix().getWhitespace().contains("\r\n")) return "\r\n";
                }
                for (Comment comment : prefix.getComments()) {
                    if (comment.getSuffix().contains("\r\n")) return "\r\n";
                }
                if (!cu.getImports().isEmpty() &&
                        cu.getImports().get(0).getPrefix().getWhitespace().contains("\r\n")) {
                    return "\r\n";
                }
                if (cu.getEof().getWhitespace().contains("\r\n")) return "\r\n";
                return "\n";
            }

            private String normalizeAnnotationText(String text) {
                return text.replaceAll("\\s+", " ")
                           .replaceFirst("\\s*\\(\\s*", "(")
                           .replaceAll("\\s+\\)$", ")")
                           .trim();
            }

            /** Returns the indentation (characters after the last newline or carriage return) from a whitespace string. */
            private String extractIndentation(String whitespace) {
                int lastLf = whitespace.lastIndexOf('\n');
                if (lastLf >= 0) return whitespace.substring(lastLf + 1);
                int lastCr = whitespace.lastIndexOf('\r');
                return lastCr >= 0 ? whitespace.substring(lastCr + 1) : whitespace;
            }
        };
    }
}
