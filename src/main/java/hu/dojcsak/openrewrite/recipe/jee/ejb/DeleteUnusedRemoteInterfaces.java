package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveImplements;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.newSetFromMap;

/**
 * Deletes {@code @Remote} EJB interfaces that {@link MarkRemoteEjbs} previously flagged with a TODO
 * comment and that turned out, after manual review, to be unused: run this once a developer has
 * migrated whichever interfaces actually needed a REST endpoint (removing their TODO marker as part
 * of that work). Everything still carrying the marker is assumed to be leftover boilerplate.
 * <p>
 * A marked interface is only deleted when it has no reference anywhere in the file set other than
 * appearing directly in some other type's {@code implements}/interface-{@code extends} list - that
 * reference is stripped via {@link RemoveImplements} rather than left dangling. Any other reference
 * (field, parameter, return type, cast, generic, or its simple name appearing inside a string
 * literal - a heuristic for JNDI-style lookups) blocks deletion; the file and its TODO marker are
 * left untouched for manual review.
 * <p>
 * Must be run across the whole multi-module reactor in a single pass: in split
 * interface/implementation module layouts, a per-module run would never see the implementing class
 * and could misjudge a still-implemented interface as unused.
 * <p>
 * Deletion decisions rely on type attribution and a best-effort string-literal scan; they cannot see
 * reflective or otherwise non-literal lookups. Always compile/build the project after running this
 * recipe before committing.
 */
@Slf4j
@Value
@EqualsAndHashCode(callSuper = false)
public class DeleteUnusedRemoteInterfaces extends ScanningRecipe<DeleteUnusedRemoteInterfaces.Acc> {

    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    String displayName = "Delete unused @Remote EJB interfaces";

    String description = "Deletes @Remote EJB interfaces previously marked by MarkRemoteEjbs that have no " +
            "remaining reference anywhere in the file set other than an implements/extends clause, which is " +
            "stripped along with the deleted interface. Interfaces with any other reference - a field, " +
            "parameter, return type, or a simple-name match inside a string literal (JNDI-style lookup " +
            "heuristic) - are left untouched for manual review. Only interfaces are deleted; @Remote bean " +
            "classes are never removed. Run across the full multi-module reactor in one pass, and rebuild " +
            "the project afterwards to catch any reference this recipe's heuristics could not see.";

    public static class Acc {
        final Map<String, Path> candidateFqnToPath = new ConcurrentHashMap<>();
        final Set<String> otherUsageFqns = newSetFromMap(new ConcurrentHashMap<>());
        final Set<String> stringLiteralTokens = newSetFromMap(new ConcurrentHashMap<>());
    }

    @Override
    public Acc getInitialValue(ExecutionContext ctx) {
        return new Acc();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Acc acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (classDecl.getKind() == J.ClassDeclaration.Kind.Type.Interface &&
                        MarkRemoteEjbs.hasRemoteTodoMarker(classDecl) &&
                        classDecl.getType() != null) {
                    J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                    acc.candidateFqnToPath.put(classDecl.getType().getFullyQualifiedName(), cu.getSourcePath());
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }

            @Override
            public <N extends NameTree> N visitTypeName(N name, ExecutionContext ctx) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(name.getType());
                if (fq != null) {
                    J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    boolean isDirectImplements = enclosing != null &&
                            enclosing.getImplements() != null &&
                            enclosing.getImplements().contains(name);
                    if (!isDirectImplements) {
                        acc.otherUsageFqns.add(fq.getFullyQualifiedName());
                    }
                }
                return super.visitTypeName(name, ctx);
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, ExecutionContext ctx) {
                if (literal.getValue() instanceof String) {
                    Matcher m = IDENTIFIER_TOKEN.matcher((String) literal.getValue());
                    while (m.find()) {
                        acc.stringLiteralTokens.add(m.group());
                    }
                }
                return super.visitLiteral(literal, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Acc acc) {
        Set<String> deletableFqns = acc.candidateFqnToPath.keySet().stream()
                .filter(fqn -> !acc.otherUsageFqns.contains(fqn))
                .filter(fqn -> !acc.stringLiteralTokens.contains(simpleName(fqn)))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Path> deletablePaths = deletableFqns.stream()
                .map(acc.candidateFqnToPath::get)
                .collect(Collectors.toSet());

        Map<Path, String> blockedPathToFqn = acc.candidateFqnToPath.entrySet().stream()
                .filter(e -> !deletableFqns.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    if (deletablePaths.contains(cu.getSourcePath())) {
                        logOnce(ctx, "deleted:" + cu.getSourcePath(), () ->
                                log.warn("Deleting unused @Remote interface at {}", cu.getSourcePath()));
                        return null;
                    }
                    String blockedFqn = blockedPathToFqn.get(cu.getSourcePath());
                    if (blockedFqn != null) {
                        logOnce(ctx, "skipped:" + blockedFqn, () ->
                                log.warn("Skipped deletion of @Remote interface '{}' at {}: still referenced - " +
                                                "review and remove manually if unused",
                                        blockedFqn, cu.getSourcePath()));
                    }
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                for (String fqn : deletableFqns) {
                    doAfterVisit(new RemoveImplements(fqn, null).getVisitor());
                }
                return super.visitCompilationUnit(cu, ctx);
            }
        };
    }

    // getVisitor(Acc) reruns fresh every recipe cycle (typically 2-3 per run), so without this guard
    // every warning for a file that's never modified (blocked candidates) would repeat once per cycle.
    // ExecutionContext is the one piece of state that persists across cycles within a single run.
    private static void logOnce(ExecutionContext ctx, String key, Runnable action) {
        Set<String> logged = ctx.computeMessageIfAbsent(
                "hu.dojcsak.openrewrite.recipe.jee.ejb.DeleteUnusedRemoteInterfaces.logged",
                k -> ConcurrentHashMap.<String>newKeySet());
        if (logged.add(key)) {
            action.run();
        }
    }

    private static String simpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }
}
