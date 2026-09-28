package com.friendspharma.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sales% for one product — the six percentages that derive each unit's sale
 * price from MRP.
 *
 * <p>Every field is nullable on purpose. Null means "this unit is not priced",
 * which the SRS treats as not sellable in that unit, and is meaningfully
 * different from 0 (priced at full MRP). Because a null also has to be able to
 * <em>clear</em> a previously set percentage, the DTO carries explicit
 * {@code clear*} flags rather than guessing from the absence of a value.
 *
 * <p>Deliberately absent: the calculated sale prices. The server derives them
 * from MRP and these percentages; there is no field to bind a forged price to.
 */
@Data
public class SalesPercentRequest {

    /* ── Regular customer (wholesale / retail) ────────────────────────────── */

    @DecimalMin(value = "0.0", message = "Box % must be zero or more.")
    @DecimalMax(value = "100.0", message = "Box % cannot exceed 100.")
    @Digits(integer = 4, fraction = 4, message = "Box % may have at most 4 digits and 4 decimals.")
    private BigDecimal boxPct;

    @DecimalMin(value = "0.0", message = "Strip % must be zero or more.")
    @DecimalMax(value = "100.0", message = "Strip % cannot exceed 100.")
    @Digits(integer = 4, fraction = 4)
    private BigDecimal stripPct;

    @DecimalMin(value = "0.0", message = "PCS % must be zero or more.")
    @DecimalMax(value = "100.0", message = "PCS % cannot exceed 100.")
    @Digits(integer = 4, fraction = 4)
    private BigDecimal pcsPct;

    /* ── Special customer ─────────────────────────────────────────────────── */

    @DecimalMin(value = "0.0", message = "Special box % must be zero or more.")
    @DecimalMax(value = "100.0", message = "Special box % cannot exceed 100.")
    @Digits(integer = 4, fraction = 4)
    private BigDecimal specialBoxPct;

    @DecimalMin(value = "0.0", message = "Special strip % must be zero or more.")
    @DecimalMax(value = "100.0", message = "Special strip % cannot exceed 100.")
    @Digits(integer = 4, fraction = 4)
    private BigDecimal specialStripPct;

    @DecimalMin(value = "0.0", message = "Special PCS % must be zero or more.")
    @DecimalMax(value = "100.0", message = "Special PCS % cannot exceed 100.")
    @Digits(integer = 4, fraction = 4)
    private BigDecimal specialPcsPct;

    /* ── Explicit clears ──────────────────────────────────────────────────── */
    /*
     * A PUT sends the row as it should now be, so an omitted percentage would
     * normally mean "leave it alone". These flags say "unset it" — needed
     * because null is a meaningful stored value here, not just an absence.
     */
    private boolean clearBox;
    private boolean clearStrip;
    private boolean clearPcs;
    private boolean clearSpecialBox;
    private boolean clearSpecialStrip;
    private boolean clearSpecialPcs;

    /**
     * Applies one set of percentages to many products at once.
     *
     * <p>Pricing is nearly always negotiated per manufacturer — a company gives
     * a trade rate that applies to its whole range — so the target can be given
     * two ways:
     *
     * <ul>
     *   <li>{@code productIds} — an explicit list, for hand-picked rows</li>
     *   <li>{@code scope} — a filter, for "every product of this company"</li>
     * </ul>
     *
     * A scope is what makes company-wide pricing practical: ticking boxes only
     * reaches the page on screen, so a 200-product manufacturer would otherwise
     * mean paging through the whole catalogue.
     *
     * <p>Exactly one of the two must be supplied.
     */
    @Data
    public static class Bulk {

        /** Explicit targets. Leave null when using {@link #scope}. */
        @Size(max = 2000, message = "A single bulk update is limited to 2000 products.")
        private List<Long> productIds;

        /** Filter-based targets. Leave null when using {@link #productIds}. */
        @Valid
        private Scope scope;

        @NotNull(message = "Percentages are required.")
        @Valid
        private SalesPercentRequest percentages;

        /**
         * When false (the default) a product that already has a percentage for a
         * unit keeps it, and only unpriced units are filled in. When true every
         * targeted product is overwritten. Defaulting to false means a bulk
         * apply cannot silently wipe hand-tuned prices.
         */
        private boolean overwriteExisting;

        /** True when the caller supplied a scope rather than an id list. */
        public boolean usesScope() {
            return scope != null && (productIds == null || productIds.isEmpty());
        }
    }

    /**
     * Which products a scoped bulk apply targets. Mirrors the grid's own
     * filters, so "what I am looking at" and "what I am about to change" are
     * described the same way.
     *
     * <p>At least one narrowing field is required — an empty scope would mean
     * "reprice the entire catalogue", which is never a deliberate click.
     */
    @Data
    public static class Scope {
        private Long   companyId;
        private Long   categoryId;
        private Long   genericId;
        /** ACTIVE / INACTIVE / ALL. Defaults to ACTIVE. */
        private String status;
        /** null = all, TRUE = already priced, FALSE = unpriced only. */
        private Boolean configured;

        public boolean isEmpty() {
            return companyId == null && categoryId == null && genericId == null;
        }
    }
}
