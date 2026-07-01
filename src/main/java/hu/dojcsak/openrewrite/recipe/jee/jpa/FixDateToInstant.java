package hu.dojcsak.openrewrite.recipe.jee.jpa;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;

/**
 * Replaces {@code date.toInstant()} with {@code Instant.ofEpochMilli(date.getTime())} for
 * expressions statically typed as {@code java.util.Date}.
 * <p>
 * Hibernate returns {@code java.sql.Date} instances for {@code @Temporal(TemporalType.DATE)}
 * mapped fields, even though the field/getter is declared as {@code java.util.Date}.
 * {@code java.sql.Date.toInstant()} always throws {@code UnsupportedOperationException} by JDK
 * specification, so any {@code .toInstant()} call on such a value fails at runtime.
 * {@code Instant.ofEpochMilli(date.getTime())} is exactly how {@code java.util.Date.toInstant()}
 * itself is implemented, so the replacement is behavior-preserving for genuine
 * {@code java.util.Date} instances and a fix for {@code java.sql.Date} ones — no need to trace
 * the value back to a {@code @Temporal} source.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FixDateToInstant extends Recipe {

    String displayName = "Fix java.sql.Date.toInstant() UnsupportedOperationException";

    String description = "Replaces `date.toInstant()` with `Instant.ofEpochMilli(date.getTime())` " +
            "on java.util.Date-typed expressions. Hibernate returns java.sql.Date instances for " +
            "@Temporal(TemporalType.DATE) mapped fields, and java.sql.Date.toInstant() always " +
            "throws UnsupportedOperationException by JDK specification. The replacement mirrors " +
            "java.util.Date's own toInstant() implementation, so it is safe for genuine " +
            "java.util.Date instances as well.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final MethodMatcher toInstant = new MethodMatcher("java.util.Date toInstant()");

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

                if (!toInstant.matches(m) || m.getSelect() == null) {
                    return m;
                }

                m = JavaTemplate.builder("Instant.ofEpochMilli(#{any(java.util.Date)}.getTime())")
                        .imports("java.time.Instant")
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace(), m.getSelect());
                maybeAddImport("java.time.Instant");
                return m;
            }
        };
    }
}
