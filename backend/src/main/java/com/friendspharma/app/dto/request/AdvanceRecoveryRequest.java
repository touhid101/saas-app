package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Getting an advance back from a supplier.
 *
 * <p>Two ways it comes back, and they are financially different:
 *
 * <ul>
 *   <li><b>In money</b> — CASH, BANK, BKASH. Leave {@code purchaseId} null.
 *       Cash returns to the business and no bill is touched.</li>
 *   <li><b>As a credit on a later bill</b> — mode {@code ADJUSTMENT}, with
 *       {@code purchaseId} naming that bill. No money moves; the advance is
 *       consumed by settling the invoice.</li>
 * </ul>
 *
 * <p>Recording the second as if it were the first would show cash arriving that
 * never did, and leave the new invoice looking unpaid.
 */
@Data
public class AdvanceRecoveryRequest {

    @NotNull(message = "The supplier returning the advance is required")
    private Long supplierId;

    /** The bill this advance is being credited against, for an ADJUSTMENT. */
    private Long purchaseId;

    /** Defaults to today when omitted. */
    private LocalDate recoveryDate;

    @NotNull(message = "Recovery amount is required")
    @Positive(message = "A recovery must be greater than zero")
    private BigDecimal amount;

    /** CASH / BANK / CHEQUE / BKASH / NAGAD / ADJUSTMENT. */
    @NotNull(message = "How the advance came back is required")
    private String paymentMode;

    private String bankName;
    private String chequeNo;
    private LocalDate chequeDate;
    private String referenceNo;
    private String remarks;
}
