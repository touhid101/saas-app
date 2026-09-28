package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money going out to a supplier.
 *
 * <p>{@code purchaseId} is optional on purpose. Paying against a named invoice
 * is the common case and lets the server refuse an overpayment, but suppliers
 * are frequently settled with one cheque covering several bills. Requiring an
 * allocation there would force whoever is at the counter to invent a split, and
 * an invented split is worse than an honest on-account entry.
 */
@Data
public class SupplierPaymentRequest {

    @NotNull(message = "The supplier being paid is required")
    private Long supplierId;

    /** The purchase being settled, or null to pay on account. */
    private Long purchaseId;

    /** Defaults to today when omitted. */
    private LocalDate paymentDate;

    @NotNull(message = "Payment amount is required")
    @Positive(message = "A payment must be greater than zero")
    private BigDecimal amount;

    /** CASH / BANK / CHEQUE / BKASH / NAGAD / ADJUSTMENT. */
    @NotNull(message = "How the payment was made is required")
    private String paymentMode;

    /**
     * The part of this payment that settles no bill and is recoverable.
     *
     * <p>Leave null and let {@code payInvoiceDiscount} derive it, or set it
     * directly for an advance paid for some other reason. Paying more than an
     * invoice owes is only accepted when the excess is declared here — an
     * undeclared overpayment is far more often a typo than a rebate.
     */
    private BigDecimal advanceAmount;

    /**
     * Pay the invoice discount along with the bill.
     *
     * <p>The trade practice this exists for: on a discounted invoice the gross
     * is handed over and the supplier returns the discount later. Setting this
     * makes the server take the advance from the invoice own discount figure,
     * so the amount recorded as recoverable is the invoice, not a number
     * retyped at the counter.
     */
    private Boolean payInvoiceDiscount;
    private String bankName;
    private String chequeNo;
    private LocalDate chequeDate;
    private String referenceNo;
    private String remarks;
}
