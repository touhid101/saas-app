package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the supplier-payment screens read back.
 *
 * <p>Four shapes, kept together because they are four views of one account and
 * splitting them across files makes it harder to see that they agree.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupplierPaymentResponse {

    private Long       id;
    private String     voucherNo;
    private LocalDate  paymentDate;
    private Long       supplierId;
    private String     supplierName;

    /** Null for a payment on account. */
    private Long       purchaseId;
    private String     purchaseInvoiceNo;

    private BigDecimal amount;
    private String     paymentMode;
    private String     bankName;
    private String     chequeNo;
    private LocalDate  chequeDate;
    private String     referenceNo;
    private String     remarks;

    /** PAYMENT, or ADVANCE_RECOVERY when the supplier gave money back. */
    private String     entryType;
    /** MANUAL, or INVOICE when mirrored from the cash keyed on the purchase. */
    private String     source;
    /** The slice of this payment that settles no bill and is recoverable. */
    private BigDecimal advanceAmount;

    /** "Y" live, "C" cancelled. */
    private String     status;
    private String     cancelReason;

    /* ══════════════════════════════════════════════════════════════════════ */

    /** One approved purchase and what it still owes. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PayableInvoice {
        private Long       purchaseId;
        private String     invoiceNo;
        private LocalDate  transactionDate;
        private String     paymentType;
        private BigDecimal billed;
        /** Cash handed over as the invoice was entered. */
        private BigDecimal paidAtInvoice;
        /** Everything paid since, from the ledger. */
        private BigDecimal paidLater;
        /** Purchase returns credited against the account. */
        private BigDecimal returnCredit;
        private BigDecimal due;
        private Integer    ageDays;
        /**
         * The invoice's own discount. Carried so the pay screen can offer to
         * hand it over with the bill using the figure on the invoice rather
         * than one retyped at the counter.
         */
        private BigDecimal invoiceDiscAmt;
    }

    /** A supplier's account: what is owed, and how old it is. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Account {
        private Long       supplierId;
        private String     supplierName;
        private String     mobileNo;
        private String     responsiblePerson;
        private BigDecimal creditLimit;
        private BigDecimal openingBal;
        private Integer    invoices;
        private BigDecimal billed;
        private BigDecimal paid;
        private BigDecimal returnCredit;
        /**
         * Returned, but not against any one bill.
         *
         * <p>A credit note the supplier raised against the account rather than
         * against an invoice — goods sent back after the bill was settled, or a
         * rebate to take off the next order. It reduces what is owed without
         * belonging to any one invoice, so it sits beside {@code onAccount}
         * rather than inside the ageing buckets, and needs no separate
         * "apply to next purchase" step: the balance is already lower, and the
         * next invoice raises it by less than its face value.
         */
        private BigDecimal unappliedCredit;
        /**
         * Paid, but not against any one bill.
         *
         * <p>Carried separately rather than spread across the ageing buckets:
         * deciding which invoice an unallocated cheque settled is an allocation
         * nobody made. So the buckets stay invoice-level and reconcile as
         * {@code outstanding = opening + buckets - onAccount}.
         */
        private BigDecimal onAccount;
        /**
         * Paid beyond the bills and not yet returned — money the supplier
         * holds of ours. The one figure in this DTO that runs the other way.
         */
        private BigDecimal advanceBalance;
        private BigDecimal outstanding;
        /**
         * Outstanding less the advance. Positive: we owe them. Negative: on
         * net they owe us, which is what an unrecovered advance means once it
         * exceeds the bills.
         */
        private BigDecimal netPosition;

        private BigDecimal due0To30;
        private BigDecimal due31To60;
        private BigDecimal due61To90;
        private BigDecimal due90Plus;

        private LocalDate  firstPurchase;
        private LocalDate  lastPurchase;
        private LocalDate  lastPaymentDate;
        private BigDecimal lastPaymentAmount;

        /**
         * Outstanding as a share of the agreed credit limit, or null when no
         * limit is on file. Null rather than zero — "no limit recorded" and
         * "nothing used" are different facts, and showing 0% for the first
         * would read as headroom that nobody has agreed to.
         */
        private BigDecimal creditUsedPct;
        /** Set when outstanding is over the agreed limit. */
        private String     creditWarning;
    }

    /** Supplier performance over a window. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Performance {
        private Long       supplierId;
        private String     supplierName;
        private String     mobileNo;
        private BigDecimal creditLimit;
        private Integer    invoices;
        private BigDecimal billed;
        private BigDecimal paid;
        private BigDecimal due;
        private Integer    returnCount;
        private BigDecimal returned;
        /** Returned value as a percentage of purchases — the quality signal. */
        private BigDecimal returnPct;
        /**
         * Amount-weighted days between invoice and payment. Null when nothing
         * in the window was paid against a named invoice, because on-account
         * money has no bill to be timed from.
         */
        private BigDecimal avgDaysToPay;
        private LocalDate  lastPurchase;
    }

    /** Advances a supplier holds, and what has come back. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Advance {
        private Long       supplierId;
        private String     supplierName;
        private BigDecimal advanced;
        private BigDecimal recovered;
        private BigDecimal balance;
        private LocalDate  firstAdvance;
        private LocalDate  lastMovement;
    }

    /** One purchase whose invoice-time advance was restated to the invoice discount. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AdvanceRestatement {
        private Long       purchaseId;
        private String     invoiceNo;
        private String     supplierName;
        private BigDecimal advanceBefore;
        private BigDecimal advanceAfter;
        private BigDecimal movedToBill;
        private String     paymentStatusBefore;
        private String     paymentStatusAfter;
        private boolean    ledgerAdjusted;
    }

    /** One line of a running supplier statement. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatementLine {
        private LocalDate  date;
        /** PURCHASE | RETURN | PAYMENT. */
        private String     kind;
        private String     docNo;
        /** Raises what is owed. */
        private BigDecimal debit;
        /** Reduces it. */
        private BigDecimal credit;
        private String     mode;
        private String     note;
        private BigDecimal balance;
    }

    /** The pay-a-supplier screen in one call. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SupplierDetail {
        private Account                account;
        private List<PayableInvoice>   openInvoices;
        private List<SupplierPaymentResponse> recentPayments;
        /** Null when the supplier holds nothing of ours. */
        private Advance                advance;
    }
}
