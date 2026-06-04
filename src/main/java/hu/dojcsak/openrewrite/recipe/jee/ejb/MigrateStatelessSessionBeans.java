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
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;
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
 *   <li>Flags {@code mappedName} and {@code description} with TODO comments for manual review</li>
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
            "Removes @Local and @LocalBean from business interfaces. " +
            "Flags mappedName and description attributes with TODO comments for manual review. " +
            "Session beans implementing a @Remote interface are not migrated.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final AnnotationMatcher statelessMatcher = new AnnotationMatcher("@javax.ejb.Stateless");
            private final AnnotationMatcher singletonMatcher = new AnnotationMatcher("@javax.ejb.Singleton");
            private final AnnotationMatcher localMatcher = new AnnotationMatcher("@javax.ejb.Local");
            private final AnnotationMatcher localBeanMatcher = new AnnotationMatcher("@javax.ejb.LocalBean");
            private final AnnotationMatcher startupMatcher = new AnnotationMatcher("@javax.ejb.Startup");
            private final AnnotationMatcher remoteMatcher = new AnnotationMatcher("@javax.ejb.Remote");

            @Override
            public J.ClassDeclaration visitClassDeclaration(
                    J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Remove @Local and @LocalBean from business interfaces
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
                        .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "spring-context"))
                        .build()
                        .apply(getCursor(), cd.getCoordinates().addAnnotation(
                                Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport("org.springframework.stereotype.Service", false);

                List<String> warnings = new ArrayList<>();
                if (hasNonLiteralName) {
                    warnings.add("name attribute could not be automatically migrated — set the @Service bean name manually");
                }
                if (hasMappedName) {
                    String mappedNameSrc = MigrateEjbAnnotations.getStringAttributeSource(ejbAnnotation, "mappedName");
                    String mappedNameLabel = mappedNameSrc != null ? "mappedName = " + mappedNameSrc : "mappedName";
                    warnings.add(mappedNameLabel + " could not be automatically migrated — configure the JNDI binding in Spring manually");
                }
                if (hasDescription) {
                    warnings.add("description attribute has no Spring equivalent — consider preserving it as a code comment");
                }
                if (hasStartup) {
                    warnings.add("@Startup removed — Spring @Service is lazy by default; add @Lazy(false) if eager initialization is required");
                }
                if (!warnings.isEmpty()) {
                    cd = flagWithTodoComment(cd, "TODO: " + String.join("; ", warnings));
                }

                return cd;
            }

            private J.ClassDeclaration removeLocalFromInterface(J.ClassDeclaration cd) {
                boolean hasLocal = cd.getLeadingAnnotations().stream().anyMatch(localMatcher::matches);
                boolean hasLocalBean = cd.getLeadingAnnotations().stream().anyMatch(localBeanMatcher::matches);
                if (!hasLocal && !hasLocalBean) {
                    return cd;
                }
                // In CRLF files the first annotation's prefix is "" (the \r\n before it lives in the
                // class declaration prefix). After removing all annotations the class prefix's trailing
                // \r\n and the first modifier's \r\n combine into a blank line. Detect this up front.
                boolean firstAnnotationHasNoNewline = !cd.getLeadingAnnotations().isEmpty() &&
                        !cd.getLeadingAnnotations().get(0).getPrefix().getWhitespace().contains("\n");
                List<J.Annotation> annotations = new ArrayList<>(cd.getLeadingAnnotations());
                annotations.removeIf(a -> localMatcher.matches(a) || localBeanMatcher.matches(a));
                cd = cd.withLeadingAnnotations(annotations);
                if (annotations.isEmpty() && firstAnnotationHasNoNewline) {
                    cd = stripOneLeadingNewlineFromFirstToken(cd);
                }
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

    // Adds a TODO line comment before the class declaration.
    //
    // The comment must NOT be placed in cd.getPrefix(): import management (AddImport/RemoveImport)
    // manipulates cd.prefix when rearranging the imports section and would move the comment to an
    // unexpected position (e.g. before the import statements). Instead the comment is attached to
    // the prefix of the first token that import management never touches:
    //   1. First leading annotation (e.g. @Service added by this recipe)
    //   2. First modifier (e.g. public) — fallback when no annotations are present
    //   3. The class/interface/enum keyword — last resort
    //
    // The comment suffix is \n (or \r\n for CRLF files) so the following token stays on the next
    // line. The blank line that was in cd.prefix separates the TODO from the preceding import.
    private static J.ClassDeclaration flagWithTodoComment(J.ClassDeclaration cd, String message) {
        String cdWs = cd.getPrefix().getWhitespace();
        String newline = cdWs.contains("\r\n") ? "\r\n" : "\n";

        List<J.Annotation> annotations = cd.getLeadingAnnotations();
        if (!annotations.isEmpty()) {
            J.Annotation first = annotations.get(0);
            Space prefix = first.getPrefix();
            Comment comment = new TextComment(false, " " + message, newline, Markers.EMPTY);
            List<Comment> comments = new ArrayList<>(prefix.getComments());
            comments.add(comment);
            List<J.Annotation> newAnnotations = new ArrayList<>(annotations);
            newAnnotations.set(0, first.withPrefix(prefix.withComments(comments)));
            return cd.withLeadingAnnotations(newAnnotations);
        }
        List<J.Modifier> modifiers = cd.getModifiers();
        if (!modifiers.isEmpty()) {
            J.Modifier first = modifiers.get(0);
            Space prefix = first.getPrefix();
            Comment comment = new TextComment(false, " " + message, prefix.getWhitespace(), Markers.EMPTY);
            List<Comment> comments = new ArrayList<>(prefix.getComments());
            comments.add(comment);
            List<J.Modifier> newModifiers = new ArrayList<>(modifiers);
            newModifiers.set(0, first.withPrefix(prefix.withComments(comments).withWhitespace("")));
            return cd.withModifiers(newModifiers);
        }
        J.ClassDeclaration.Kind kind = cd.getPadding().getKind();
        Space kindPrefix = kind.getPrefix();
        Comment comment = new TextComment(false, " " + message, newline, Markers.EMPTY);
        List<Comment> comments = new ArrayList<>(kindPrefix.getComments());
        comments.add(comment);
        return cd.getPadding().withKind(kind.withPrefix(kindPrefix.withComments(comments)));
    }

    private static J.ClassDeclaration stripOneLeadingNewlineFromFirstToken(J.ClassDeclaration cd) {
        if (!cd.getModifiers().isEmpty()) {
            J.Modifier first = cd.getModifiers().get(0);
            List<J.Modifier> mods = new ArrayList<>(cd.getModifiers());
            mods.set(0, first.withPrefix(stripOneLeadingNewline(first.getPrefix())));
            return cd.withModifiers(mods);
        }
        J.ClassDeclaration.Kind kind = cd.getPadding().getKind();
        return cd.getPadding().withKind(kind.withPrefix(stripOneLeadingNewline(kind.getPrefix())));
    }

    private static Space stripOneLeadingNewline(Space space) {
        String ws = space.getWhitespace();
        if (ws.startsWith("\r\n")) return space.withWhitespace(ws.substring(2));
        if (ws.startsWith("\n")) return space.withWhitespace(ws.substring(1));
        return space;
    }
}
