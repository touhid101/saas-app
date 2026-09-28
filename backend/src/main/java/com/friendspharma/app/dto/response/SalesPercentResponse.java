package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sales% for one product: the six percentages, and the sale price each one
 * produces from MRP.
 *
 * <p>The prices are always sent alongside the percentages so the grid can show
 * both without recomputing — and so what the user sees is what the server
 * calculated, not a second implementation of the formula that could disagree.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SalesPercentResponse {

    private Long   productId;
    private String productCode;
    private String productName;
    private String sku;
    private String genericName;
    private String companyName;
    private String categoryName;
    private String unitName;

    /** The basis for every calculation below. */
    private BigDecimal mrp;

    /** Pieces per box / strip, so the grid can show what a "box" actually is. */
    private Integer pcsPerBox;
    private Integer pcsPerStrip;

    /** Regular customer (wholesale / retail). */
    private UnitPrice box;
    private UnitPrice strip;
    private UnitPrice pcs;

    /** Special customer. */
    private UnitPrice specialBox;
    private UnitPrice specialStrip;
    private UnitPrice specialPcs;

    /** True when BOX, STRIP and PCS all have a regular percentage set. */
    /**
     * Landed cost per box, strip and piece — the weighted average across the
     * lots on hand in this branch.
     *
     * <p>Shown beside the MRP so a percentage can be judged against what the
     * stock actually cost, not only against list price. Null where the product
     * holds no stock and there is therefore nothing to compare against.
     */
    private StockResponses.CostBlock cost;

    /**
     * True when at least one configured price has fallen at or below what that
     * unit cost.
     *
     * <p>A discount that looks reasonable against MRP can still be under cost
     * once the trade price and IGTA are counted — that is exactly how a product
     * ends up selling at a loss without anyone noticing.
     */
    private Boolean belowCost;

    private Boolean regularComplete;
    /** True when nothing at all is configured — the grid's "needs pricing" flag. */
    private Boolean unconfigured;

    private String status;

    /**
     * One unit's pricing.
     *
     * <p>A null {@code pct} means the unit is not priced, and {@code price} and
     * {@code discountAmount} are null with it — deliberately not zero, so the UI
     * can tell "free" apart from "not set up".
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UnitPrice {
        private BigDecimal pct;
        /**
         * The unit's own MRP share less the percentage.
         *
         * <p>A strip of a 100-piece box starts at MRP÷100 before any discount,
         * not at the full MRP. Pricing every unit off the raw MRP put a single
         * tablet of a ৳400 box at ৳364.
         */
        private BigDecimal price;
        /** The unit's share of MRP, before the percentage. */
        private BigDecimal mrp;
        /** mrp − price, i.e. the money taken off. */
        private BigDecimal discountAmount;
        /** What one of this unit cost, for comparison against {@link #price}. */
        private BigDecimal cost;
        /** True when this unit's price is at or below its cost. */
        private Boolean belowCost;
        /** False when pct is null. */
        private Boolean configured;
    }

    /** Result of applying one set of percentages across many products. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BulkResult {
        private int totalRequested;
        private int updated;
        private int skipped;
        private int failed;
        /** Per-product outcome, so a partial apply is explainable. */
        private List<RowResult> results;

        @Data
        @Builder
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class RowResult {
            private Long   productId;
            private String productName;
            /** UPDATED / SKIPPED / FAILED */
            private String status;
            private String reason;
        }
    }
}
