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
 *   <li>Flags {@code mappedName} and {@code description} with a search marker for manual review</li>
 *   <li>Skips beans that implement a {@code @Remote} interface (directly or through supertype),
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
            "Flags mappedName and description attributes with a search marker for manual review. " +
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

                J.Annotation ejbAnnotation = hasStateless ?
                        ejbAnnotation(cd, statelessMatcher) :
                        ejbAnnotation(cd, singletonMatcher);
                String name = MigrateEjbAnnotations.getStringAttribute(ejbAnnotation, "name");
                // name can be a constant reference (non-literal); getStringAttribute returns null in that case
                boolean hasNonLiteralName = name == null && MigrateEjbAnnotations.hasAttribute(ejbAnnotation, "name");
                // mappedName is a vendor-specific JNDI name with no Spring equivalent; flag when non-empty.
                // Empty string is the EJB default and is treated as absent.
                String mappedName = MigrateEjbAnnotations.getStringAttribute(ejbAnnotation, "mappedName");
                boolean hasMappedName = StringUtils.isNotEmpty(mappedName) ||
                        (mappedName == null && MigrateEjbAnnotations.hasAttribute(ejbAnnotation, "mappedName"));
                String ejbDescription = MigrateEjbAnnotations.getStringAttribute(ejbAnnotation, "description");
                boolean hasDescription = StringUtils.isNotEmpty(ejbDescription) ||
                        (ejbDescription == null && MigrateEjbAnnotations.hasAttribute(ejbAnnotation, "description"));

                boolean hasStartup = cd.getLeadingAnnotations().stream().anyMatch(startupMatcher::matches);

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
                String nameSource = MigrateEjbAnnotations.getStringAttributeSource(ejbAnnotation, "name");
                String template = StringUtils.isNotEmpty(name) ?
                        "@Service(" + (nameSource != null ? nameSource : "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"") + ")" :
                        "@Service";
                cd = JavaTemplate.builder(template)
                        .imports("org.springframework.stereotype.Service")
                        .javaParser(JavaParser.fromJavaVersion().classpath("spring-context"))
                        .build()
                        .apply(getCursor(), cd.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.stereotype.Service", false);

                // SearchResult.found() uses computeByType((s1,s2)->s1): only the first marker survives
                // on a given node. Collect all messages and emit a single SearchResult.
                List<String> warnings = new ArrayList<>();
                if (hasNonLiteralName) {
                    warnings.add("name attribute could not be automatically migrated — set the @Service bean name manually");
                }
                if (hasMappedName) {
                    warnings.add("mappedName attribute could not be automatically migrated — configure the JNDI binding in Spring manually");
                }
                if (hasDescription) {
                    warnings.add("description attribute has no Spring equivalent — consider preserving it as a code comment");
                }
                if (hasStartup) {
                    warnings.add("@Startup removed — Spring @Service is lazy by default; add @Lazy(false) if eager initialization is required");
                }
                if (!warnings.isEmpty()) {
                    cd = SearchResult.found(cd, String.join("; ", warnings));
                }

                return cd;
            }

            private J.ClassDeclaration removeLocalFromInterface(J.ClassDeclaration cd) {
                boolean hasLocal = cd.getLeadingAnnotations().stream().anyMatch(localMatcher::matches);
                boolean hasLocalBean = cd.getLeadingAnnotations().stream().anyMatch(localBeanMatcher::matches);
                if (!hasLocal && !hasLocalBean) {
                    return cd;
                }
                List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                annotations.removeIf(a -> localMatcher.matches(a) || localBeanMatcher.matches(a));
                cd = cd.withLeadingAnnotations(annotations);
                if (hasLocal) {
                    maybeRemoveImport("javax.ejb.Local");
                }
                if (hasLocalBean) {
                    maybeRemoveImport("javax.ejb.LocalBean");
                }
                updateCursor(cd);
                return cd;
            }

            private boolean isRemoteBean(J.ClassDeclaration classDecl) {
                if (classDecl.getLeadingAnnotations().stream().anyMatch(remoteMatcher::matches)) {
                    return true;
                }
                JavaType.Class classType = TypeUtils.asClass(classDecl.getType());
                if (classType == null) {
                    // Type attribution failed — cannot inspect interface annotations.
                    // Warn when the class declares implemented interfaces, since one of them
                    // might carry @Remote and we cannot verify it.
                    if (classDecl.getImplements() != null && !classDecl.getImplements().isEmpty()) {
                        J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                        log.warn("Could not resolve type for '{}' in {}: " +
                                        "cannot verify @Remote — migrating to @Service, please verify manually",
                                classDecl.getSimpleName(), cu.getSourcePath());
                    }
                    return false;
                }
                return implementsRemoteInterface(classType);
            }

            // Walks the superclass chain and each interface's own superinterface hierarchy so that
            // @Remote is detected regardless of whether it appears directly on the implemented
            // interface, through an abstract base class, or via interface extension
            // (e.g. ServiceRemote extends @Remote BaseRemote).
            private boolean implementsRemoteInterface(JavaType.Class classType) {
                if (classType == null || "java.lang.Object".equals(classType.getFullyQualifiedName())) {
                    return false;
                }
                if (classType.getInterfaces().stream().anyMatch(this::isRemoteInterface)) {
                    return true;
                }
                return implementsRemoteInterface(TypeUtils.asClass(classType.getSupertype()));
            }

            // Checks whether an interface type (or any of its superinterfaces) carries @Remote.
            private boolean isRemoteInterface(JavaType.FullyQualified iface) {
                if (iface == null) {
                    return false;
                }
                if (iface.getAnnotations().stream()
                        .anyMatch(a -> "javax.ejb.Remote".equals(a.getFullyQualifiedName()))) {
                    return true;
                }
                return iface.getInterfaces().stream().anyMatch(this::isRemoteInterface);
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
