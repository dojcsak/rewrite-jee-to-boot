package hu.dojcsak.openrewrite.recipe.jee.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Migrates EJB session beans to Spring services:
 * <ul>
 *   <li>{@code @Stateless} → {@code @Service} (preserving {@code name} as {@code @Service("name")})</li>
 *   <li>{@code @Singleton} → {@code @Service} (removing {@code @Startup})</li>
 *   <li>Removes {@code @Local} and {@code @LocalBean} from bean classes and interfaces</li>
 *   <li>Skips beans that implement an interface annotated with {@code @Remote},
 *       or that are directly annotated with {@code @Remote}</li>
 * </ul>
 */
@Slf4j
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateStatelessSessionBeans extends Recipe {

    String displayName = "Migrate @Stateless and @Singleton EJBs to @Service";

    String description = "Replaces @Stateless and @Singleton EJB annotations with Spring @Service. " +
            "Removes @Local, @LocalBean, and @Startup annotations. " +
            "Removes @Local from business interfaces. " +
            "Session beans implementing a @Remote interface are not migrated.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final AnnotationMatcher statelessMatcher  = new AnnotationMatcher("@javax.ejb.Stateless");
            private final AnnotationMatcher singletonMatcher  = new AnnotationMatcher("@javax.ejb.Singleton");
            private final AnnotationMatcher localMatcher      = new AnnotationMatcher("@javax.ejb.Local");
            private final AnnotationMatcher localBeanMatcher  = new AnnotationMatcher("@javax.ejb.LocalBean");
            private final AnnotationMatcher startupMatcher    = new AnnotationMatcher("@javax.ejb.Startup");
            private final AnnotationMatcher remoteMatcher     = new AnnotationMatcher("@javax.ejb.Remote");

            @Override
            public J.ClassDeclaration visitClassDeclaration(
                    J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Remove @Local from business interfaces
                if (cd.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
                    return removeLocalFromInterface(cd);
                }

                boolean hasStateless = cd.getLeadingAnnotations().stream().anyMatch(statelessMatcher::matches);
                boolean hasSingleton = cd.getLeadingAnnotations().stream().anyMatch(singletonMatcher::matches);
                if (!hasStateless && !hasSingleton) {
                    return cd;
                }

                // Beans with @Remote are distributed components — skip migration
                if (isRemoteBean(cd)) {
                    J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                    log.warn("Skipped @Remote EJB bean '{}' in {}: manual migration to Spring required",
                            cd.getSimpleName(), cu.getSourcePath());
                    return SearchResult.found(cd,
                            "Skipped: bean implements @Remote interface — manual migration to Spring required");
                }

                String name = hasStateless ?
                        MigrateEjbAnnotations.getStringAttribute(ejbAnnotation(cd, statelessMatcher), "name") :
                        MigrateEjbAnnotations.getStringAttribute(ejbAnnotation(cd, singletonMatcher), "name");

                // Remove all EJB class-level annotations
                List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                annotations.removeIf(a ->
                        statelessMatcher.matches(a) || singletonMatcher.matches(a) ||
                        localMatcher.matches(a) || localBeanMatcher.matches(a) ||
                        startupMatcher.matches(a));
                cd = cd.withLeadingAnnotations(annotations);
                maybeRemoveImport("javax.ejb.Stateless");
                maybeRemoveImport("javax.ejb.Singleton");
                maybeRemoveImport("javax.ejb.Local");
                maybeRemoveImport("javax.ejb.LocalBean");
                maybeRemoveImport("javax.ejb.Startup");
                updateCursor(cd);

                // Add @Service, preserving the name attribute when present
                String template = (StringUtils.isNotEmpty(name)) ?
                        "@Service(\"" + name + "\")" :
                        "@Service";
                cd = JavaTemplate.builder(template)
                        .imports("org.springframework.stereotype.Service")
                        .javaParser(JavaParser.fromJavaVersion().classpath("spring-context"))
                        .build()
                        .apply(getCursor(), cd.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.stereotype.Service", false);

                return cd;
            }

            private J.ClassDeclaration removeLocalFromInterface(J.ClassDeclaration cd) {
                if (cd.getLeadingAnnotations().stream().noneMatch(localMatcher::matches)) {
                    return cd;
                }
                List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                annotations.removeIf(localMatcher::matches);
                maybeRemoveImport("javax.ejb.Local");
                return cd.withLeadingAnnotations(annotations);
            }

            private boolean isRemoteBean(J.ClassDeclaration classDecl) {
                if (classDecl.getLeadingAnnotations().stream().anyMatch(remoteMatcher::matches)) {
                    return true;
                }
                JavaType.Class classType = TypeUtils.asClass(classDecl.getType());
                if (classType == null) {
                    return false;
                }
                return classType.getInterfaces().stream()
                        .anyMatch(iface -> iface.getAnnotations().stream()
                                .anyMatch(a -> "javax.ejb.Remote".equals(a.getFullyQualifiedName())));
            }

            private J.Annotation ejbAnnotation(J.ClassDeclaration cd, AnnotationMatcher matcher) {
                return cd.getLeadingAnnotations().stream()
                        .filter(matcher::matches)
                        .findFirst()
                        .orElse(null);
            }
        };
    }
}
