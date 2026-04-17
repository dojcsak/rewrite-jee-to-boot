package hu.dojcsak.openrewrite.recipe;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * For every field whose declared type matches {@code fieldType}:
 * <ul>
 *   <li>If the field is <em>not referenced</em> anywhere in the class → removes it
 *       (including annotations, leading comment, and now-unused imports for both
 *       the field type and its annotations).</li>
 *   <li>If the field <em>is referenced</em> → prepends an idempotent {@code // TODO}
 *       line comment and logs a WARN message, so the developer knows to eliminate
 *       the usages before the field can be deleted.</li>
 *   <li>If type information is unavailable, the field is conservatively treated
 *       as referenced (not deleted).</li>
 * </ul>
 */
@Slf4j
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveFieldByType extends Recipe {

    @Option(displayName = "Field type",
            description = "The fully qualified name of the type whose field declarations should be removed.",
            example = "javax.ejb.SessionContext")
    String fieldType;

    @Override
    public String getDisplayName() {
        return "Remove field by type";
    }

    @Override
    public String getDescription() {
        return "Removes every field declaration whose type matches the given fully qualified class name. " +
               "If the field is unreferenced, it is deleted together with its annotations and leading comment block, " +
               "and the imports of the field type and its annotations are cleaned up. " +
               "If the field is still referenced in the class, a // TODO comment is prepended instead " +
               "and a WARN message is logged, prompting the developer to eliminate the usages first. " +
               "If type information is unavailable the field is conservatively treated as referenced. " +
               "TODO comment insertion is idempotent: the comment is not added twice.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                            ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                List<Statement> statements = cd.getBody().getStatements();
                List<Statement> retained = new ArrayList<>(statements.size());
                boolean changed = false;

                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                String sourcePath = cu != null ? cu.getSourcePath().toString() : "unknown";

                for (Statement stmt : statements) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        if (TypeUtils.isOfClassType(vd.getType(), fieldType)) {
                            String fieldName = vd.getVariables().isEmpty() ? "?"
                                    : vd.getVariables().get(0).getSimpleName();
                            if (isUsedInClass(cd, vd)) {
                                // Field is still referenced — add a TODO comment and log a warning
                                retained.add(withTodoComment(vd));
                                log.warn("[RemoveFieldByType] Field '{}' of type '{}' is still in use in {} " +
                                         "-- added TODO comment instead of removing.",
                                        fieldName, fieldType, sourcePath);
                            } else {
                                // Field is unreferenced — remove it and clean up imports
                                maybeRemoveImport(fieldType);
                                for (J.Annotation ann : vd.getLeadingAnnotations()) {
                                    if (ann.getType() instanceof JavaType.FullyQualified) {
                                        maybeRemoveImport(
                                                ((JavaType.FullyQualified) ann.getType()).getFullyQualifiedName());
                                    }
                                }
                            }
                            changed = true;
                            continue;
                        }
                    }
                    retained.add(stmt);
                }

                if (!changed) {
                    return cd;
                }
                return cd.withBody(cd.getBody().withStatements(retained));
            }
        };
    }

    // -------------------------------------------------------------------------
    // Usage detection
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if any identifier in {@code classDecl} references
     * one of the variables declared in {@code fieldDecl} outside its own
     * declaration site.
     *
     * <p>OpenRewrite sets {@link J.Identifier#getFieldType()} to the
     * {@link JavaType.Variable} both at the declaration site (the variable
     * name) and at every usage site.  Counting all such occurrences and
     * comparing to the number of declared variables therefore gives us
     * "used outside the declaration" when the total count exceeds the
     * declaration count.
     */
    private static boolean isUsedInClass(J.ClassDeclaration classDecl,
                                         J.VariableDeclarations fieldDecl) {
        List<JavaType.Variable> varTypes = new ArrayList<>();
        for (J.VariableDeclarations.NamedVariable nv : fieldDecl.getVariables()) {
            if (nv.getVariableType() != null) {
                varTypes.add(nv.getVariableType());
            }
        }
        if (varTypes.isEmpty()) {
            // No type information — conservative: treat as used
            return true;
        }

        AtomicInteger count = new AtomicInteger(0);
        new JavaIsoVisitor<AtomicInteger>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicInteger cnt) {
                if (varTypes.contains(identifier.getFieldType())) {
                    cnt.incrementAndGet();
                }
                return super.visitIdentifier(identifier, cnt);
            }
        }.visit(classDecl, count);

        // Declaration site: each NamedVariable contributes exactly one identifier
        return count.get() > varTypes.size();
    }

    // -------------------------------------------------------------------------
    // TODO comment injection
    // -------------------------------------------------------------------------

    private J.VariableDeclarations withTodoComment(J.VariableDeclarations vd) {
        Space prefix = vd.getPrefix();

        String todoText = " TODO: Remove field '" + simpleTypeName() + "' - still in use." +
                          " Eliminate all usages first, then delete this field.";

        // Idempotency: skip if the comment is already present
        for (Comment c : prefix.getComments()) {
            if (c instanceof TextComment && todoText.equals(((TextComment) c).getText())) {
                return vd;
            }
        }

        String indent = lastLineOf(prefix.getWhitespace());

        TextComment todo = new TextComment(false, todoText, "\n" + indent, Markers.EMPTY);

        List<Comment> newComments = new ArrayList<>();
        newComments.add(todo);
        newComments.addAll(prefix.getComments());

        return vd.withPrefix(Space.build(prefix.getWhitespace(), newComments));
    }

    /** Returns the text after the last newline in {@code ws} (the indentation). */
    private static String lastLineOf(String ws) {
        if (ws == null) return "";
        int last = ws.lastIndexOf('\n');
        return last >= 0 ? ws.substring(last + 1) : ws;
    }

    /** Simple name extracted from the fully qualified type name. */
    private String simpleTypeName() {
        int dot = fieldType.lastIndexOf('.');
        return dot >= 0 ? fieldType.substring(dot + 1) : fieldType;
    }
}
