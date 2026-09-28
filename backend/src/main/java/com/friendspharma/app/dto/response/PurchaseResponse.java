package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full purchase invoice — header, calculated totals and every line.
 * Returned by view, create, update and approve.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PurchaseResponse {

    private Long   id;
    /** Generated: PU + yyMM + 5 digits. */
    private String invoiceNo;
    /** Generated: 6-digit purchase batch reference. */
    private String batchNo;
    private String challanNo;

    private LocalDate transactionDate;
    /** Always "PU" for this endpoint. */
    private String    transactionType;
    /** MNP or MRP — which branch of the line formula was applied. */
    private String    purchaseType;
    private String    productType;
    private String    paymentType;

    private Ref supplier;
    private Ref branch;

    /* ── Calculated totals ── */
    private BigDecimal totalAmount;
    private BigDecimal invoiceDisPct;
    private BigDecimal invoiceDisAmt;
    private BigDecimal totalDiscount;
    private BigDecimal totalTax;
    private BigDecimal serviceCharge;
    /** [V68] Supplier cashback taken off this invoice. */
    private BigDecimal cashbackAmount;
    private BigDecimal amountToBePaid;
    private BigDecimal givenAmount;
    /** Of the cash handed over, the part the supplier owes back. */
    private BigDecimal advanceAmount;
    /** Positive = still owed to the supplier. */
    /**
     * Everything paid against this invoice, however it was paid — the cash at
     * the counter plus anything booked later on the payment screen. Distinct
     * from {@code givenAmount}, which is only the first of those.
     */
    private BigDecimal paidAmount;
    /** Credit from purchase returns taken against the account. */
    private BigDecimal returnCredit;
    private BigDecimal dueAmount;

    private String remarks;

    /** D / P / A. */
    private String  status;
    /** DRAFT / PENDING / APPROVED — the same value spelled for display. */
    private String  statusLabel;
    /** False once approved: an approved invoice has moved stock and is frozen. */
    private Boolean editable;

    private String fromSource;
    private Integer lineCount;

    private List<PurchaseLineResponse> lines;

    /* ── Audit ── */
    private String        createdBy;
    private LocalDateTime createdDate;
    private String        updatedBy;
    private LocalDateTime updatedDate;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PurchaseLineResponse {
        private Long   id;
        private Long   productId;
        private String productCode;
        private String productName;
        private String genericName;
        private String companyName;
        private String unitName;

        private BigDecimal quantity;
        /** [V67] Free units received with this line. Zero on an ordinary line. */
        private BigDecimal bonusQty;
        /** [V69] PURCHASE, or GIFT for a different product given free. */
        private String lineKind;
        /** [V67] quantity + bonusQty — what actually reached the shelf. */
        private BigDecimal receivedQty;
        /**
         * [V67] What a unit really cost once the bonus is counted:
         * net line total ÷ received units. Equal to the line rate when there is
         * no bonus, and the figure the batch and every COGS calculation use.
         */
        private BigDecimal effectiveUnitCost;
        /** Snapshot of the product's TP price — "TP_PRICE" in the MNP formula. */
        private BigDecimal mnpPrice;
        private BigDecimal mrpPrice;
        private BigDecimal igta;
        private BigDecimal tpPct;
        private BigDecimal discountPct;
        private BigDecimal flatDiscount;
        /** Calculated server-side; never accepted from the client. */
        private BigDecimal totalPrice;

        private String    batchNo;
        private LocalDate expiryDate;
        private String    salesType;
        private Integer   noOfPcs;
        /** [V67] (quantity + bonusQty) × noOfPcs — the pieces that reached the shelf. */
        private BigDecimal totalPcs;

        /**
         * What this delivery actually cost, per box, strip and piece.
         *
         * <p>Derived from the line's own total over the pieces it brought in,
         * so it carries the trade price, IGTA and the line discount together —
         * the number the stock is valued at and profit is measured against.
         * {@link #mnpPrice} above is the trade price <em>before</em> any of
         * that, and the two routinely differ.
         *
         * <p>Same shape as the inventory reports use, so a lot costed here reads
         * identically on Batch Stock.
         */
        private StockResponses.CostBlock cost;
    }

    /** Generic {id, name} reference. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Ref {
        private Long   id;
        private String name;

        public static Ref of(Long id, String name) {
            return id == null ? null : Ref.builder().id(id).name(name).build();
        }
    }
}
