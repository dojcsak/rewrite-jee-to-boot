package hu.dojcsak.openrewrite.recipe.jee.tx;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.java.tree.TypeTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replaces JEE transaction annotations with their Spring equivalents:
 * <ul>
 *   <li>{@code @TransactionManagement} is removed (Spring Boot manages transactions via AOP).</li>
 *   <li>{@code @TransactionAttribute} on a class or method is replaced with
 *       {@code @Transactional}, mapping {@code TransactionAttributeType} values to
 *       {@code Propagation} values. {@code REQUIRED} is the Spring default so no
 *       explicit propagation is emitted in that case.</li>
 *   <li>If both {@code @TransactionAttribute} and {@code @Transactional} coexist on a class,
 *       the existing {@code @Transactional} is removed and replaced by the mapped one.</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTransactionAnnotations extends Recipe {

    private static final AnnotationMatcher TX_ATTR_MATCHER =
            new AnnotationMatcher("@javax.ejb.TransactionAttribute");
    private static final AnnotationMatcher TX_MGMT_MATCHER =
            new AnnotationMatcher("@javax.ejb.TransactionManagement");
    private static final AnnotationMatcher TRANSACTIONAL_MATCHER =
            new AnnotationMatcher("@org.springframework.transaction.annotation.Transactional");

    // Stubs so JavaTemplate can parse @Transactional / Propagation without needing
    // spring-tx on the target project's classpath (classpath("spring-tx") only works
    // when the JAR is accessible via a URLClassLoader, which is not always the case).
    private static final String TRANSACTIONAL_STUB =
            "package org.springframework.transaction.annotation;\n" +
            "import java.lang.annotation.*;\n" +
            "@Target({ElementType.TYPE, ElementType.METHOD})\n" +
            "@Retention(RetentionPolicy.RUNTIME)\n" +
            "public @interface Transactional {\n" +
            "    Propagation propagation() default Propagation.REQUIRED;\n" +
            "    boolean readOnly() default false;\n" +
            "}\n";

    private static final String PROPAGATION_STUB =
            "package org.springframework.transaction.annotation;\n" +
            "public enum Propagation {\n" +
            "    REQUIRED, SUPPORTS, MANDATORY, REQUIRES_NEW, NOT_SUPPORTED, NEVER, NESTED\n" +
            "}\n";

    private static JavaParser.Builder<?, ?> springTxParser() {
        return JavaParser.fromJavaVersion().dependsOn(TRANSACTIONAL_STUB, PROPAGATION_STUB);
    }

    @Override
    public String getDisplayName() {
        return "Migrate @TransactionAttribute to @Transactional";
    }

    @Override
    public String getDescription() {
        return "Migrates JEE transaction annotations to their Spring equivalents on both class and method level. " +
               "Removes @TransactionManagement (Spring Boot manages transactions via AOP). " +
               "Replaces @TransactionAttribute with @Transactional, mapping TransactionAttributeType values " +
               "to Spring Propagation. REQUIRED is omitted from the generated @Transactional as it is the " +
               "Spring default. If an existing @Transactional is present on the class alongside " +
               "@TransactionAttribute, it is removed and replaced by the mapped one.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            // -----------------------------------------------------------------
            // Class level
            // -----------------------------------------------------------------

            @Override
            public J.ClassDeclaration visitClassDeclaration(
                    J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                boolean hasTxMgmt = cd.getLeadingAnnotations().stream().anyMatch(TX_MGMT_MATCHER::matches);
                boolean hasTxAttr = cd.getLeadingAnnotations().stream().anyMatch(TX_ATTR_MATCHER::matches);

                if (!hasTxMgmt && !hasTxAttr) {
                    return cd;
                }

                // 1. Remove @TransactionManagement (Spring Boot needs no explicit transaction strategy)
                if (hasTxMgmt) {
                    List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                    annotations.removeIf(TX_MGMT_MATCHER::matches);
                    cd = cd.withLeadingAnnotations(annotations);
                    maybeRemoveImport("javax.ejb.TransactionManagement");
                    maybeRemoveImport("javax.ejb.TransactionManagementType");
                }

                // 2. Replace @TransactionAttribute → @Transactional
                if (hasTxAttr) {
                    J.Annotation txAttr = cd.getLeadingAnnotations().stream()
                            .filter(TX_ATTR_MATCHER::matches)
                            .findFirst()
                            .get();
                    String propagation = extractPropagation(txAttr);

                    // Remove @TransactionAttribute and any pre-existing @Transactional on the class
                    List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                    annotations.removeIf(TX_ATTR_MATCHER::matches);
                    annotations.removeIf(TRANSACTIONAL_MATCHER::matches);
                    cd = cd.withLeadingAnnotations(annotations);
                    maybeRemoveImport("javax.ejb.TransactionAttribute");
                    maybeRemoveImport("javax.ejb.TransactionAttributeType");
                    updateCursor(cd);

                    // JavaTemplate.apply()'s default autoFormat pass can reflow the class declaration's
                    // extends/implements clauses (e.g. collapsing a manually-indented multi-line layout,
                    // as generated by Andromda, onto a single unindented line) even though only a leading
                    // annotation is being inserted here - restore them verbatim afterward, since this
                    // recipe never intends to touch that formatting.
                    JLeftPadded<TypeTree> extendsBeforeTemplate = cd.getPadding().getExtends();
                    JContainer<TypeTree> implementsBeforeTemplate = cd.getPadding().getImplements();
                    if ("REQUIRED".equals(propagation)) {
                        cd = JavaTemplate.builder("@Transactional")
                                .imports("org.springframework.transaction.annotation.Transactional")
                                .javaParser(springTxParser())
                                .build()
                                .apply(getCursor(), cd.getCoordinates().addAnnotation(
                                        Comparator.comparing(J.Annotation::getSimpleName)));
                        maybeAddImport("org.springframework.transaction.annotation.Transactional", false);
                    } else {
                        cd = JavaTemplate.builder(
                                        "@Transactional(propagation = Propagation." + propagation + ")")
                                .imports("org.springframework.transaction.annotation.Transactional",
                                         "org.springframework.transaction.annotation.Propagation")
                                .javaParser(springTxParser())
                                .build()
                                .apply(getCursor(), cd.getCoordinates().addAnnotation(
                                        Comparator.comparing(J.Annotation::getSimpleName)));
                        maybeAddImport("org.springframework.transaction.annotation.Transactional", false);
                        maybeAddImport("org.springframework.transaction.annotation.Propagation", false);
                    }
                    if (extendsBeforeTemplate != null) {
                        cd = cd.getPadding().withExtends(extendsBeforeTemplate);
                    }
                    if (implementsBeforeTemplate != null) {
                        cd = cd.getPadding().withImplements(implementsBeforeTemplate);
                    }
                }

                return cd;
            }

            // -----------------------------------------------------------------
            // Method level
            // -----------------------------------------------------------------

            @Override
            public J.MethodDeclaration visitMethodDeclaration(
                    J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

                if (m.getLeadingAnnotations().stream().noneMatch(TX_ATTR_MATCHER::matches)) {
                    return m;
                }

                J.Annotation txAttr = m.getLeadingAnnotations().stream()
                        .filter(TX_ATTR_MATCHER::matches)
                        .findFirst()
                        .get();
                String propagation = extractPropagation(txAttr);

                List<J.Annotation> annotations = new ArrayList<>(m.getLeadingAnnotations());
                annotations.removeIf(TX_ATTR_MATCHER::matches);
                m = m.withLeadingAnnotations(annotations);
                maybeRemoveImport("javax.ejb.TransactionAttribute");
                maybeRemoveImport("javax.ejb.TransactionAttributeType");
                updateCursor(m);

                // JavaTemplate.apply()'s default autoFormat pass can reflow the method declaration's
                // throws clause (e.g. collapsing a manually-indented multi-line layout, as generated
                // by Andromda, onto a single unindented line) even though only a leading annotation is
                // being inserted here - restore it verbatim afterward, since this recipe never intends
                // to touch that formatting.
                JContainer<NameTree> throwsBeforeTemplate = m.getPadding().getThrows();
                if ("REQUIRED".equals(propagation)) {
                    m = JavaTemplate.builder("@Transactional")
                            .imports("org.springframework.transaction.annotation.Transactional")
                            .javaParser(springTxParser())
                            .build()
                            .apply(getCursor(), m.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.transaction.annotation.Transactional", false);
                } else {
                    m = JavaTemplate.builder(
                                    "@Transactional(propagation = Propagation." + propagation + ")")
                            .imports("org.springframework.transaction.annotation.Transactional",
                                     "org.springframework.transaction.annotation.Propagation")
                            .javaParser(springTxParser())
                            .build()
                            .apply(getCursor(), m.getCoordinates().addAnnotation(
                                    Comparator.comparing(J.Annotation::getSimpleName)));
                    maybeAddImport("org.springframework.transaction.annotation.Transactional", false);
                    maybeAddImport("org.springframework.transaction.annotation.Propagation", false);
                }
                if (throwsBeforeTemplate != null) {
                    m = m.getPadding().withThrows(throwsBeforeTemplate);
                }

                return m;
            }

            // -----------------------------------------------------------------
            // Helpers
            // -----------------------------------------------------------------

            /**
             * Extracts the Spring {@code Propagation} name from a {@code @TransactionAttribute}
             * annotation. Returns {@code "REQUIRED"} when no explicit value is given
             * (matching the JEE default).
             */
            private String extractPropagation(J.Annotation annotation) {
                if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                    return "REQUIRED";
                }
                Expression arg = annotation.getArguments().get(0);
                // Unwrap "value = TransactionAttributeType.X" assignment form
                if (arg instanceof J.Assignment) {
                    arg = ((J.Assignment) arg).getAssignment();
                }
                if (arg instanceof J.FieldAccess) {
                    return ((J.FieldAccess) arg).getName().getSimpleName();
                }
                if (arg instanceof J.Identifier) {
                    return ((J.Identifier) arg).getSimpleName();
                }
                return "REQUIRED";
            }
        };
    }
}
