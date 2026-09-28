package com.friendspharma.app.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a requisition becomes once it is grouped by supplier.
 *
 * <p>This is a proposal, not a result. It is computed and shown before anything
 * is created, because the split is the moment a buyer commits money and it is
 * the last point at which changing your mind is free.
 */
public class RequisitionSplitResponse {

    /** One requisition line, with the supplier the system thinks it should go to. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Line {
        private Long       reqLineId;
        private Long       productId;
        private String     productName;
        private Long       companyId;
        private String     companyName;

        private BigDecimal boxes;
        private String     unit;
        /** TP + IGTA per box — exactly what the purchase order will be billed at. */
        private BigDecimal unitCost;
        private BigDecimal lineTotal;
        /**
         * What a box actually cost the last time it was bought, if it ever was.
         *
         * <p>Shown beside the projected cost rather than used as it. The order
         * is priced from the product master, so quoting a historic rate as the
         * total would be a preview that does not match what gets created — but
         * a buyer still wants to see that the price has moved since last time.
         */
        private BigDecimal lastPaidPerBox;

        private Long       supplierId;
        private String     supplierName;

        /**
         * Why this supplier: LAST_PURCHASE, MANUFACTURER, OVERRIDE, or NONE.
         *
         * <p>Shown to the buyer rather than kept internal. "You last bought this
         * from Metro" and "Metro is the only supplier who lists this maker" are
         * different strengths of evidence, and the second is worth a second look.
         */
        private String     basis;
        private LocalDate  lastPurchasedOn;
        private String     priority;
    }

    /** All the lines going to one supplier — one purchase order in waiting. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Group {
        private Long       supplierId;
        private String     supplierName;
        private String     companyName;
        private int        lineCount;
        private BigDecimal total;
        private List<Line> lines;
    }

    /** The whole proposal. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Preview {
        private Long        requisitionId;
        private String      requisitionNo;
        private String      stage;
        private int         lineCount;
        private BigDecimal  total;
        private List<Group> groups;
        /**
         * Lines nobody could be found for.
         *
         * <p>Kept separate rather than dropped or lumped under a guess. A line
         * that quietly vanishes between the requisition and the order is the one
         * that is missed on the shelf a fortnight later.
         */
        private List<Line>  unassigned;
        /** True when every line has a supplier, so the split can go ahead as shown. */
        private boolean     ready;
    }

    /** What the split actually created. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Result {
        private Long           requisitionId;
        private String         requisitionNo;
        private String         stage;
        private int            ordersCreated;
        private BigDecimal     total;
        private List<Created>  orders;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Created {
        private Long       purchaseId;
        private String     invoiceNo;
        private Long       supplierId;
        private String     supplierName;
        private int        lineCount;
        private BigDecimal total;
        private String     status;
    }
}
