package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One payment made to a supplier.
 *
 * <p>The counterpart of {@link PosDeliveryCollection} on the buying side. Before
 * this existed the only record of paying a supplier was {@code GIVEN_AMOUNT} on
 * the purchase invoice — the cash handed over as the invoice was keyed in — so a
 * payment made a week later had nowhere to go and outstanding could only rise.
 *
 * <p>A payment may name one purchase or none. On-account payments matter:
 * suppliers are commonly settled with a single cheque covering several bills,
 * and forcing a split across invoices at the counter invents an allocation
 * nobody actually agreed.
 */
@Entity
@Table(name = "POS_SUPPLIER_PAYMENT")
@Getter
@Setter
@NoArgsConstructor
public class PosSupplierPayment extends BaseEntity {

    /** Live. */
    public static final String ACTIVE = "Y";
    /** Reversed — kept, never deleted, with the reason recorded. */
    public static final String CANCELLED = "C";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SUPPLIER_PAYMENT")
    @SequenceGenerator(name = "SQ_SUPPLIER_PAYMENT", sequenceName = "PHARMA.SQ_SUPPLIER_PAYMENT", allocationSize = 1)
    @Column(name = "PID_PAYMENT")
    private Long pidPayment;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_SUPPLIER", nullable = false)
    private Long pidSupplier;

    /** The purchase settled, or null for a payment on account. */
    @Column(name = "PID_TRAN_MST")
    private Long pidTranMst;

    @Column(name = "VOUCHER_NO", nullable = false, length = 50)
    private String voucherNo;

    @Column(name = "PAYMENT_DATE", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "AMOUNT", nullable = false, precision = 14, scale = 4)
    private BigDecimal amount;

    /** CASH / BANK / CHEQUE / BKASH / NAGAD / ADJUSTMENT. */
    @Column(name = "PAYMENT_MODE", nullable = false, length = 30)
    private String paymentMode;

    @Column(name = "BANK_NAME", length = 100)
    private String bankName;

    @Column(name = "CHEQUE_NO", length = 50)
    private String chequeNo;

    @Column(name = "CHEQUE_DATE")
    private LocalDate chequeDate;

    @Column(name = "REFERENCE_NO", length = 100)
    private String referenceNo;

    @Column(name = "REMARKS", length = 500)
    private String remarks;

    /** Money out. */
    public static final String PAYMENT = "PAYMENT";
    /** Money back from the supplier, reducing an advance. */
    public static final String ADVANCE_RECOVERY = "ADVANCE_RECOVERY";
    /** Entered on the payment screen. */
    public static final String MANUAL = "MANUAL";
    /** Mirrored from the cash recorded on the purchase itself. */
    public static final String FROM_INVOICE = "INVOICE";

    @Column(name = "ENTRY_TYPE", nullable = false, length = 20)
    private String entryType = PAYMENT;

    @Column(name = "SOURCE", nullable = false, length = 20)
    private String source = MANUAL;

    /**
     * The slice of this payment that settles no bill.
     *
     * <p>Typically the invoice discount paid up front on the understanding that
     * the supplier returns it. Carried on the payment rather than as a separate
     * document because it is one movement of money, and splitting it into two
     * vouchers would misreport what left the bank.
     */
    @Column(name = "ADVANCE_AMOUNT", nullable = false, precision = 14, scale = 4)
    private BigDecimal advanceAmount = BigDecimal.ZERO;
    @Column(name = "CANCEL_REASON", length = 255)
    private String cancelReason;
}
