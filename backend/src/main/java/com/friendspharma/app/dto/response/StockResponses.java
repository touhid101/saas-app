package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read models for branch-wise batch stock.
 *
 * <p>Every quantity carries its unit in its name. {@code qtyPcs} is pieces;
 * {@code qtyBox} and {@code qtyStrip} are derived for display. An unnamed
 * "quantity" is what let the old code add boxes to pieces.
 */
public final class StockResponses {

    private StockResponses() {}

    /* ── Slow-moving stock ──────────────────────────────────────────────── */

    /** A lot with stock that the branch has not sold from for a while. */
    @Getter @Builder
    public static class SlowMovingRow {
        private Long       stockId;
        private Long       productId;
        private String     productName;
        private String     sku;
        private String     companyName;
        private String     batchNo;
        private LocalDate  expiryDate;
        private Integer    daysToExpiry;
        private BigDecimal qtyPcs;
        private String     qtyDisplay;
        private BigDecimal costPerPcs;
        private BigDecimal stockValue;
        /** Last SALE of the product in this branch; null when it has never sold here. */
        private LocalDate  lastSold;
        private Integer    daysIdle;
        private LocalDate  receivedDate;
    }

    /* ── Batch stock ──────────────────────────────────────────────────────── */

    @Getter @Builder
    public static class BatchStockRow {
        private Long       stockId;
        private Long       branchId;
        private String     branchName;
        private Long       productId;
        private String     productName;
        private String     sku;
        private String     batchNo;
        private LocalDate  expiryDate;
        private Integer    daysToExpiry;
        /** OK / NEAR_EXPIRY / EXPIRED */
        private String     expiryStatus;

        private BigDecimal qtyPcs;
        private BigDecimal qtyBox;
        private BigDecimal qtyStrip;
        /** Shelf-readable form, e.g. {@code "8 box 2 strip 3 pcs"}. */
        private String     qtyDisplay;

        private Integer    pcsPerBox;
        private Integer    pcsPerStrip;

        private BigDecimal costPerPcs;
        private BigDecimal mrpPrice;
        private BigDecimal stockValue;

        /** Landed cost of this lot, per box, strip and piece. */
        private CostBlock  cost;

        private BigDecimal receivedPcs;
        private BigDecimal issuedPcs;
        private LocalDate  receivedDate;
        private String     status;

        /** Every sale price this lot can go out at — box, strip, piece; regular and special. */
        private UnitPriceBlock prices;
    }

    /* ── Per-product rollup ───────────────────────────────────────────────── */

    @Getter @Builder
    public static class ProductStockRow {
        private Long       productId;
        private String     productName;
        private String     sku;
        /** Scanned at the POS counter. Null where the product has none on file. */
        private String     barcode;
        private String     companyName;
        private String     imgColor;

        /**
         * Public URL of the product photograph, or null where none is on file.
         *
         * The column has always existed and Product Master has always shown it;
         * this list simply never carried it, so the till had a name and a price
         * where a picture of the box would identify the medicine faster.
         */
        private String     productImageUrl;

        private BigDecimal stockPcs;
        private BigDecimal stockBox;
        private BigDecimal stockStrip;
        private String     stockDisplay;

        private Integer    pcsPerBox;
        private Integer    pcsPerStrip;

        private BigDecimal stockValue;

        /** Weighted average landed cost across the lots on hand. */
        private CostBlock  cost;

        private Integer    alertQty;
        /** OK / LOW / OUT */
        private String     stockStatus;

        private long       batchCount;
        private LocalDate  earliestExpiry;

        /** Every sale price this product can go out at. */
        private UnitPriceBlock prices;
    }

    /* ── Allocation ───────────────────────────────────────────────────────── */

    /** One lot's contribution to an allocation. */
    @Getter @Builder
    public static class AllocationLine {
        private Long       stockId;
        private String     batchNo;
        private LocalDate  expiryDate;
        private BigDecimal availablePcs;
        private BigDecimal allocatedPcs;
        private BigDecimal costPerPcs;
        /** {@code allocatedPcs × costPerPcs} — the COGS contribution of this lot. */
        private BigDecimal costValue;
    }

    @Getter @Builder
    public static class StockAllocation {
        private Long       branchId;
        private Long       productId;
        private String     productName;

        private BigDecimal requestedQty;
        private String     requestedUnit;
        private BigDecimal requestedPcs;

        private BigDecimal allocatedPcs;
        private BigDecimal shortfallPcs;
        private boolean    fullyAllocated;

        /** Weighted cost of everything allocated — what actual profit subtracts. */
        private BigDecimal totalCost;

        private List<AllocationLine> lines;
    }

    /* ── Movement ledger ──────────────────────────────────────────────────── */

    @Getter @Builder
    public static class MovementRow {
        private Long          movementId;
        private Long          stockId;
        private Long          productId;
        private String        productName;
        private String        batchNo;
        private LocalDate     expiryDate;

        private String        movementType;
        /** I / O */
        private String        direction;
        private BigDecimal    qtyPcs;
        /** The same movement in boxes and strips, from the lot's own packing. */
        private BigDecimal    qtyBox;
        private BigDecimal    qtyStrip;
        private String        qtyDisplay;
        private Integer       pcsPerBox;
        private Integer       pcsPerStrip;
        /** Signed for charting: negative on the way out. */
        private BigDecimal    signedQtyPcs;
        private BigDecimal    balanceAfter;
        private BigDecimal    costPerPcs;

        /** What this movement cost, per box, strip and piece. */
        private CostBlock     cost;

        private String        refType;
        private Long          refMst;
        private Long          refDtl;
        private LocalDate     movementDate;
        private String        remarks;
        private LocalDateTime insertedDate;
        private String        insertedBy;
    }

    /* ── Dashboard ────────────────────────────────────────────────────────── */

    @Getter @Builder
    public static class StockDashboard {
        private Long       branchId;
        private String     branchName;
        private BigDecimal totalStockValue;
        private BigDecimal totalPcs;
        private long       productsInStock;
        private long       lowStockCount;
        private long       outOfStockCount;
        private long       nearExpiryCount;
        private long       expiredCount;
    }

    /* ── Buy price ────────────────────────────────────────────────────────── */

    /**
     * What the stock actually cost, per box, strip and piece.
     *
     * <p>This is the <em>landed</em> cost of the batch — total invoice value
     * divided by pieces received, so it carries the trade price, IGTA and any
     * line discount that was applied. It is not
     * {@code POS_PRODUCT_INFO.BUY_PRICE}, which is the product's current list
     * cost and says nothing about what any particular delivery was billed at.
     * Two lots of the same product routinely differ, which is exactly why COGS
     * is computed per batch.
     *
     * <p>Per box and per strip are the piece cost scaled by the packing frozen
     * on the lot. Null where that unit has no packing configured — not zero.
     *
     * <p>On a per-product rollup the piece cost is the <b>weighted average</b>
     * across the lots on hand, which is a different number from any single lot's
     * cost and is labelled as such wherever it is shown.
     */
    @Getter @Builder
    public static class CostBlock {
        private BigDecimal costPerPcs;
        private BigDecimal costPerStrip;
        private BigDecimal costPerBox;
        private Integer    pcsPerBox;
        private Integer    pcsPerStrip;
        /** True when this is an average over several lots rather than one lot's cost. */
        private boolean    averaged;
    }

    /* ── Sale prices ──────────────────────────────────────────────────────── */

    /**
     * Every price a product can be sold at, per unit and per customer tier.
     *
     * <p>Derived from the product's Sales% against the MRP apportioned to each
     * unit — a strip of a 12-strip box starts at MRP÷12, not at MRP. A null
     * price means that unit is not priced, which the SRS treats as not sellable
     * in that unit; it is deliberately distinct from a price of zero.
     */
    @Getter @Builder
    public static class UnitPriceBlock {
        private BigDecimal boxMrp;
        private BigDecimal stripMrp;
        private BigDecimal pcsMrp;

        private BigDecimal boxPrice;
        private BigDecimal stripPrice;
        private BigDecimal pcsPrice;

        private BigDecimal specialBoxPrice;
        private BigDecimal specialStripPrice;
        private BigDecimal specialPcsPrice;

        /** The percentages the prices came from, so a report can show both. */
        private BigDecimal boxPct;
        private BigDecimal stripPct;
        private BigDecimal pcsPct;
        private BigDecimal specialBoxPct;
        private BigDecimal specialStripPct;
        private BigDecimal specialPcsPct;

        private boolean priced;
    }

    /* ── Movement summary ─────────────────────────────────────────────────── */

    /**
     * One product's movement over a period: what it opened with, everything
     * that moved it, and what it closed with.
     *
     * <p>{@code closingPcs} is stated independently rather than inferred, so the
     * row proves its own arithmetic: opening + purchased − purchaseReturned −
     * sold + salesReturned + adjusted must equal closing.
     */
    @Getter @Builder
    public static class MovementSummaryRow {
        private Long       productId;
        private String     productName;
        private String     sku;

        private BigDecimal openingPcs;
        private BigDecimal purchasedPcs;
        private BigDecimal purchaseReturnedPcs;
        private BigDecimal soldPcs;
        private BigDecimal salesReturnedPcs;
        private BigDecimal adjustedPcs;
        private BigDecimal closingPcs;

        /** Same figures in shelf terms, for the columns people actually read. */
        private String     openingDisplay;
        private String     closingDisplay;
        private BigDecimal openingBox;
        private BigDecimal openingStrip;
        private BigDecimal purchasedBox;
        private BigDecimal soldBox;
        private BigDecimal closingBox;
        private BigDecimal closingStrip;
        private Integer    pcsPerBox;
        private Integer    pcsPerStrip;

        private BigDecimal purchaseValue;
        /** Cost of what was sold — the COGS half of actual profit. */
        private BigDecimal salesCostValue;

        /** Weighted average landed cost across the lots on hand. */
        private CostBlock  cost;

        /** True when the stated closing matches the movements that produced it. */
        private boolean    balanced;

        private UnitPriceBlock prices;
    }

    /** Period totals for the movement report. */
    @Getter @Builder
    public static class MovementSummary {
        private Long       branchId;
        private LocalDate  fromDate;
        private LocalDate  toDate;
        private BigDecimal totalPurchasedPcs;
        private BigDecimal totalSoldPcs;
        private BigDecimal totalPurchaseReturnedPcs;
        private BigDecimal totalSalesReturnedPcs;
        private BigDecimal totalPurchaseValue;
        private BigDecimal totalSalesCostValue;
        private long       productCount;
        /** Rows whose closing does not match their movements. Should be zero. */
        private long       unbalancedCount;
        private List<MovementSummaryRow> rows;
    }

    /* ── Actual profit ────────────────────────────────────────────────────── */

    /**
     * One product's realised profit for a period.
     *
     * <p>Revenue is what the invoices actually charged; cost is what the
     * specific batches consumed actually cost. Not an average — a sale that took
     * 120 pieces at ৳13.93 and 12 at ৳14.09 is costed at both rates, which is
     * the only way the margin is true for that sale rather than merely plausible.
     */
    @Getter @Builder
    public static class ProfitRow {
        private Long       productId;
        private String     productName;
        private String     sku;
        private String     companyName;

        private BigDecimal soldPcs;
        private BigDecimal returnedPcs;
        /** Sold less returned — what profit is actually earned on. */
        private BigDecimal netPcs;
        private BigDecimal netBox;
        private BigDecimal netStrip;
        private String     netDisplay;
        private Integer    pcsPerBox;
        private Integer    pcsPerStrip;

        private BigDecimal revenue;
        private BigDecimal cost;
        private BigDecimal grossProfit;
        /** Profit as a percentage of revenue. Null when nothing was sold. */
        private BigDecimal marginPct;

        /** Realised average selling and cost price per piece, for comparison. */
        private BigDecimal avgSalePricePerPcs;
        private BigDecimal avgCostPerPcs;

        /**
         * The realised buy price per box, strip and piece.
         *
         * <p>Named apart from {@link #cost}, which is the period's total cost of
         * goods sold. This is the unit rate that total works out to.
         */
        private CostBlock  costPrice;

        /** True when the product sold below what it cost. */
        private boolean    soldBelowCost;
    }

    /** Period profit totals. */
    @Getter @Builder
    public static class ProfitSummary {
        private Long       branchId;
        private LocalDate  fromDate;
        private LocalDate  toDate;
        private BigDecimal totalRevenue;
        private BigDecimal totalCost;
        private BigDecimal totalGrossProfit;
        private BigDecimal totalMarginPct;
        private BigDecimal totalNetPcs;
        private long       productCount;
        /** Products sold below cost — worth seeing before the total is trusted. */
        private long       belowCostCount;
        private List<ProfitRow> rows;
    }

    /* ── Reconciliation ───────────────────────────────────────────────────── */

    /**
     * A lot whose stored balance disagrees with its own movement history.
     *
     * <p>Always expected to be empty. A row here means the balance was changed
     * outside {@code StockService}, and the movement log is the one to trust.
     */
    @Getter @Builder
    public static class ReconciliationRow {
        private Long       stockId;
        private Long       branchId;
        private Long       productId;
        private String     batchNo;
        private BigDecimal storedBalance;
        private BigDecimal ledgerBalance;
        private BigDecimal difference;
    }
}
