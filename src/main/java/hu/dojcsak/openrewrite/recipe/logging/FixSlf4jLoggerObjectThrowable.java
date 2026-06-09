package hu.dojcsak.openrewrite.recipe.logging;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fixes logger.level(throwable) and logger.level(throwable, throwable) calls that originate
 * from Log4j 1.x code where Logger.error(Object message) / Logger.error(Object message, Throwable t)
 * was used.  After migration to SLF4J those overloads no longer exist (SLF4J requires a String
 * as the message argument, and has no single-arg Throwable overload), so the calls must be
 * rewritten to logger.level(throwable.getMessage()) / logger.level(throwable.getMessage(), throwable).
 *
 * <p>Typical source: partially migrated files where imports were changed to org.slf4j.Logger
 * but the call signatures were not updated.</p>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FixSlf4jLoggerObjectThrowable extends Recipe {

    private static final Set<String> LOG_LEVELS = new HashSet<>(Arrays.asList(
            "error", "warn", "info", "debug", "trace"
    ));

    @Override
    public String getDisplayName() {
        return "Fix SLF4J logger.level(Throwable) and logger.level(Throwable, Throwable) calls";
    }

    @Override
    public String getDescription() {
        return "Rewrites logger.level(throwable) calls to logger.level(throwable.getMessage()), and " +
               "logger.level(throwable, throwable) calls to logger.level(throwable.getMessage(), throwable). " +
               "Log4j 1.x accepted any Object as the message argument and had no single-arg Throwable " +
               "overload restriction, but SLF4J requires a String as the message. " +
               "Applies to all standard log levels (error, warn, info, debug, trace) on org.slf4j.Logger.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                             ExecutionContext ctx) {
                method = super.visitMethodInvocation(method, ctx);

                String name = method.getSimpleName();
                if (!LOG_LEVELS.contains(name)) {
                    return method;
                }

                List<Expression> args = method.getArguments();
                if (args.size() != 1 && args.size() != 2) {
                    return method;
                }

                // Verify the call is on an org.slf4j.Logger
                Expression select = method.getSelect();
                if (select == null) {
                    return method;
                }
                if (!TypeUtils.isAssignableTo("org.slf4j.Logger", select.getType())) {
                    return method;
                }

                // First argument must be a Throwable, not a String
                Expression firstArg = args.get(0);
                JavaType firstType = firstArg.getType();
                if (firstType == null || firstType instanceof JavaType.Unknown) {
                    return method;
                }
                if (TypeUtils.isOfClassType(firstType, "java.lang.String")) {
                    return method;
                }
                if (!TypeUtils.isAssignableTo("java.lang.Throwable", firstType)) {
                    return method;
                }

                if (args.size() == 1) {
                    // Replace logger.level(throwable) → logger.level(throwable.getMessage())
                    JavaTemplate template = JavaTemplate
                            .builder("#{any()}." + name + "(#{any(java.lang.Throwable)}.getMessage())")
                            .build();

                    return template.apply(
                            getCursor(),
                            method.getCoordinates().replace(),
                            select,
                            firstArg
                    );
                }

                // Replace logger.level(throwable, arg2)
                //      → logger.level(throwable.getMessage(), arg2)
                JavaTemplate template = JavaTemplate
                        .builder("#{any()}." + name +
                                 "(#{any(java.lang.Throwable)}.getMessage(), #{any()})")
                        .build();

                return template.apply(
                        getCursor(),
                        method.getCoordinates().replace(),
                        select,
                        firstArg,
                        args.get(1)
                );
            }
        };
    }
}
