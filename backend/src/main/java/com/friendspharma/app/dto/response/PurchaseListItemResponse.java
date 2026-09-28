package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Slim row for the purchase history grid.
 *
 * <p>Separate from {@link PurchaseResponse} on purpose: the grid renders many
 * invoices and does not need their lines. Loading full detail per row is what
 * turns a history page into a slow one.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PurchaseListItemResponse {

    private Long      id;
    private String    invoiceNo;
    private String    batchNo;
    private String    challanNo;
    private LocalDate transactionDate;

    private Long   supplierId;
    private String supplierName;
    private String branchName;

    private String purchaseType;
    private String productType;
    private String paymentType;

    private BigDecimal totalAmount;
    private BigDecimal totalDiscount;
    private BigDecimal amountToBePaid;
    private BigDecimal givenAmount;
    /**
     * Everything paid against this invoice, however it was paid — the cash at
     * the counter plus anything booked later on the payment screen. Distinct
     * from {@code givenAmount}, which is only the first of those.
     */
    private BigDecimal paidAmount;
    /** Credit from purchase returns taken against the account. */
    private BigDecimal returnCredit;
    private BigDecimal dueAmount;
    /** Of the cash handed over, the part the supplier still owes back. */
    private BigDecimal advanceAmount;

    private Integer lineCount;

    /** D / P / A. */
    private String  status;
    private String  statusLabel;
    private Boolean editable;

    private String createdBy;
}
