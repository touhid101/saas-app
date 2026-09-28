package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Salary paid early, and taken back out of the next payslip or two.
 *
 * <p><b>Why this is not the same table as a loan.</b> An advance has no
 * schedule worth maintaining: it comes off the next run, or is spread over two
 * or three. A loan is a sum lent against a plan with a due date per
 * installment and a record of which payroll took which one. Folding them
 * together would give the advance a schedule nobody wants to keep, or the loan
 * none — and the difference is exactly what a manager is choosing between.
 *
 * <p><b>Amount and approved amount are different columns</b> because an
 * approver who cuts the figure has made a decision worth being able to see
 * afterwards. Recovery is always computed from {@link #approvedAmount}.
 *
 * <p><b>Outstanding is not stored.</b> It is approved minus recovered, and a
 * third column holding the same fact is a column that will one day disagree
 * with the other two.
 *
 * <p><b>HR does not write journals.</b> Paying an advance moves money and
 * belongs in the general ledger, but nothing here records that. Accounts raises
 * the journal from its own financial event and holds the reference — so HR can
 * be built and used before Accounts exists.
 */
@Entity @Table(name = "HR_ADVANCE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrAdvance extends BaseEntity {

    @Id @Column(name = "PID_ADVANCE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_advance")
    @SequenceGenerator(name = "seq_hr_advance", sequenceName = "SEQ_HR_ADVANCE", allocationSize = 1)
    private Long pidAdvance;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    @Column(name = "ADVANCE_NO", length = 30)   private String advanceNo;
    @Column(name = "REQUEST_DATE", nullable = false) private LocalDate requestDate;

    @Column(name = "AMOUNT", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "APPROVED_AMOUNT", precision = 18, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "RECOVERED_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal recoveredAmount = BigDecimal.ZERO;

    /** Spread over this many payslips. One means the whole thing next run. */
    @Column(name = "INSTALLMENTS")
    @Builder.Default private Integer installments = 1;

    @Column(name = "RECOVER_FROM") private LocalDate recoverFrom;
    @Column(name = "REASON", length = 1000) private String reason;

    /** See {@link HrConstants.AdvanceStatus}. */
    @Column(name = "ADVANCE_STATUS", length = 20, nullable = false)
    @Builder.Default private String advanceStatus = HrConstants.AdvanceStatus.DRAFT;

    @Column(name = "DECIDED_BY", length = 50) private String decidedBy;
    @Column(name = "DECIDED_AT")              private LocalDateTime decidedAt;
    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;

    @Column(name = "PAID_DATE")                        private LocalDate paidDate;
    @Column(name = "PAYMENT_MODE", length = 20)        private String paymentMode;
    @Column(name = "PAYMENT_REFERENCE", length = 60)   private String paymentReference;

    /** Approved minus recovered. Zero once it is closed. */
    @Transient
    public BigDecimal getOutstanding() {
        BigDecimal approved = approvedAmount == null ? BigDecimal.ZERO : approvedAmount;
        BigDecimal back     = recoveredAmount == null ? BigDecimal.ZERO : recoveredAmount;
        BigDecimal left     = approved.subtract(back);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    /** Money is out and payroll should be taking it back. */
    public boolean isRecoverable() {
        return HrConstants.AdvanceStatus.RECOVERABLE.contains(advanceStatus)
                && getOutstanding().signum() > 0;
    }

    /** What one payslip should take, given how many installments were agreed. */
    @Transient
    public BigDecimal getInstallmentAmount() {
        BigDecimal outstanding = getOutstanding();
        int n = installments == null || installments < 1 ? 1 : installments;
        if (n == 1) return outstanding;
        BigDecimal approved = approvedAmount == null ? BigDecimal.ZERO : approvedAmount;
        BigDecimal per = approved.divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.HALF_UP);
        // The last installment collects whatever rounding left behind, so the
        // total recovered is exactly the total advanced.
        return per.compareTo(outstanding) > 0 ? outstanding : per;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
