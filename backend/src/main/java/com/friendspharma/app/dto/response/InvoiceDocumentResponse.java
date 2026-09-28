package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One sale, shaped for printing.
 *
 * <p>Serves both layouts — the A4 invoice and the till receipt — from a single
 * response, so the two cannot disagree about the same sale. A receipt whose
 * discount reads differently from the invoice for the same invoice number is
 * the kind of thing a customer notices at the counter.
 *
 * <p>Every derived figure is computed here rather than in the browser: the
 * pieces behind a strip, the discount as an amount, the balance due, the total
 * in words. Two screens doing that arithmetic independently is two chances to
 * get it wrong.
 *
 * <p>The letterhead comes from ALL_BRANCH — the pharmacy's own name, address,
 * phone and logo — so a sale prints under the branch that raised it rather than
 * under anything hard-coded.
 */
@Getter
@Builder
public class InvoiceDocumentResponse {

    private Branch branch;
    private Invoice invoice;
    private Party customer;
    private List<Line> lines;
    private Totals totals;

    /** The customer's wider balance, for the reminder. Null where it does not apply. */
    private Account account;

    /** "Taka One Hundred Nineteen Only." */
    private String amountInWords;

    /* ── Letterhead ──────────────────────────────────────────────────────── */

    @Getter
    @Builder
    public static class Branch {
        /** The registered branch name, e.g. "Friends Pharma 60 Feet". */
        private String name;

        /**
         * The trading name over the door, from ALL_BRANCH.REPORT_HEADER.
         *
         * <p>Falls back to the branch name when it is not set, so a pharmacy that
         * has never filled it in still prints a heading rather than a blank.
         */
        private String tradingName;

        private String address;
        private String phone;
        private String email;

        /** Public URL of the branch logo, or null where none has been uploaded. */
        private String logoUrl;
    }

    /* ── The document ────────────────────────────────────────────────────── */

    @Getter
    @Builder
    public static class Invoice {
        private Long id;
        private String invoiceNo;
        private String challanNo;
        private LocalDate date;

        /** When this copy was printed, which is not when the sale happened. */
        private LocalDateTime printedAt;

        /** The user who raised it. */
        private String createdBy;

        private String deliveryManName;
        private String deliveryManPhone;

        private String paymentType;
        private String paymentStatus;
        private String stage;
        private String remarks;
    }

    /**
     * What this customer owes beyond the invoice in hand.
     *
     * <p>Three figures rather than one, because they answer different questions:
     * what was already outstanding, what this sale adds, and the total to
     * collect. A single number hides whether the debt is new.
     *
     * <p>Absent for a walk-in sale. "Walk-in Customer" is one shared row per
     * branch, so its balance is every anonymous credit sale added together —
     * printing that as a person's debt would be wrong and, handed across a
     * counter, alarming.
     */
    @Getter
    @Builder
    public static class Account {
        /** Unpaid invoices, newest first — the statement itself. */
        private List<OpenInvoice> invoices;

        /**
         * Invoices too old to list, where the statement had to be cut.
         *
         * <p>A customer with two thousand open invoices cannot have them all on
         * one sheet, but the grand total must still be the whole debt — so the
         * remainder is summarised in a line rather than quietly dropped.
         */
        private long moreCount;
        private BigDecimal moreDue;

        /** Everything owed, listed rows and summarised remainder together. */
        private BigDecimal grandTotal;

        /** How many invoices that covers, and the oldest of them. */
        private long openInvoices;
        private LocalDate oldestDue;

        /**
         * True when the invoice being printed is still a draft.
         *
         * <p>Every money figure in this system counts confirmed sales only — a
         * draft has reserved stock but is not yet a sale — so a draft is absent
         * from the statement below. Without saying so, the sheet would show a
         * balance due at the top and a grand total beneath it that did not
         * include it, and look simply wrong.
         */
        private boolean currentIsDraft;
    }

    /** One line of the statement. */
    @Getter
    @Builder
    public static class OpenInvoice {
        private LocalDate date;
        private String invoiceNo;
        private BigDecimal invoiceTotal;
        private BigDecimal due;

        /** Days since the invoice was raised — how long the money has been out. */
        private long ageDays;

        /** True for the invoice being printed, so the sheet can mark it. */
        private boolean current;
    }

    @Getter
    @Builder
    public static class Party {
        private String name;
        private String mobile;
        private String address;
    }

    /* ── Lines ───────────────────────────────────────────────────────────── */

    @Getter
    @Builder
    public static class Line {
        private int sl;
        private String product;
        private String batchNo;
        private LocalDate expiryDate;

        /** Price per selling unit, as printed on the pack. */
        private BigDecimal mrp;

        /** BOX, STRIP or PCS — the unit this line was sold in. */
        private String unit;

        /** How many of that unit were sold: 2 strips. */
        private BigDecimal unitQty;

        /** mrp × unitQty, before any discount. */
        private BigDecimal totalMrp;

        /** totalMrp − totalPrice, stated as money rather than a percentage. */
        private BigDecimal discountAmount;

        /** What the customer actually paid per unit. */
        private BigDecimal unitPrice;

        /** Pieces behind the units sold: 2 strips of 10 is 20. */
        private BigDecimal quantityPcs;

        private BigDecimal totalPrice;
    }

    /* ── Foot ────────────────────────────────────────────────────────────── */

    @Getter
    @Builder
    public static class Totals {
        /** What the goods would have cost at full MRP. */
        private BigDecimal totalMrp;

        /** Sum of the line totals, after line discounts. */
        private BigDecimal subtotal;

        /** totalMrp − subtotal: what the line discounts saved. */
        private BigDecimal discount;

        /** A discount on the whole invoice, beyond the line discounts. */
        private BigDecimal specialDiscount;

        private BigDecimal deliveryCharge;

        /** The amount payable. */
        private BigDecimal totalBill;

        /** Credited back on goods returned against this invoice. */
        private BigDecimal returnAmount;

        private BigDecimal paid;
        private BigDecimal balanceDue;
    }
}
