package hu.dojcsak.openrewrite.recipe.spring;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * Records, for every {@code ConvertFieldInjectionToLombokConstructorInjection} candidate class
 * (Spring stereotype with at least one {@code @Autowired} field), whether it was converted
 * to Lombok constructor injection or excluded from automatic conversion — and if excluded, why.
 */
public class FieldInjectionConversionReport extends DataTable<FieldInjectionConversionReport.Row> {

    public FieldInjectionConversionReport(Recipe recipe) {
        super(recipe,
                "Field injection to Lombok constructor injection conversion report",
                "Every Spring-stereotype class with at least one @Autowired field, and whether " +
                "it was converted to constructor injection via Lombok @RequiredArgsConstructor or " +
                "excluded from automatic conversion for safety.");
    }

    @Value
    public static class Row {

        @Column(displayName = "Source path",
                description = "The path of the source file containing the class.")
        String sourcePath;

        @Column(displayName = "Class name",
                description = "The fully qualified name of the candidate class.")
        String className;

        @Column(displayName = "Status",
                description = "CONVERTED if the class was rewritten to use constructor injection, " +
                        "EXCLUDED if it was left unchanged for manual review.")
        String status;

        @Column(displayName = "Exclusion reason",
                description = "The reason code (A/B/C/D) the class was excluded, or empty when converted. " +
                        "A = an ancestor class has its own @Autowired/uninitialized-final field; " +
                        "B = a reflective, class-based JAX-WS/CXF registration was found; " +
                        "C = a direct `new ClassName(...)` call site was found elsewhere in the codebase; " +
                        "D = an existing constructor does not match the final-field set.")
        String exclusionReason;

        @Column(displayName = "Detail",
                description = "Free-text detail explaining the status/exclusion reason " +
                        "(e.g. the conflicting ancestor class, or the call site location).")
        String detail;
    }
}
