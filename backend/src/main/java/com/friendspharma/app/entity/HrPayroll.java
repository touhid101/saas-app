package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One payroll run: this branch, this month.
 *
 * <p>DRAFT to CALCULATED to REVIEWED to APPROVED to POSTED to PAID, and
 * CANCELLED from anywhere before POSTED.
 *
 * <p><b>POSTED is where the data stops moving.</b> Up to APPROVED a run can be
 * recalculated as often as anyone likes; that is what the review step is for.
 * At POSTED the run has been handed to Accounts and a journal exists against
 * it, so the row becomes read-only: no update, no delete, no recalculation. A
 * correction is a reversal and a fresh run — the same rule the general ledger
 * lives by, and for the same reason.
 *
 * <p>The database enforces what it can ({@code ck_hr_payroll_posted}, and
 * {@code uq_hr_payroll_live_run} for one live run per period). The service
 * enforces the rest, because "you may not edit this" is a sentence, not a
 * constraint violation.
 */
@Entity @Table(name = "HR_PAYROLL", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrPayroll extends BaseEntity {

    @Id @Column(name = "PID_PAYROLL")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_payroll")
    @SequenceGenerator(name = "seq_hr_payroll", sequenceName = "SEQ_HR_PAYROLL", allocationSize = 1)
    private Long pidPayroll;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;
    @Column(name = "PID_PERIOD", nullable = false) private Long pidPeriod;

    @Column(name = "PAYROLL_NO", length = 30, nullable = false)
    private String payrollNo;

    /** See {@link HrConstants.PayrollStatus}. */
    @Column(name = "PAYROLL_STATUS", length = 20, nullable = false)
    @Builder.Default private String payrollStatus = HrConstants.PayrollStatus.DRAFT;

    @Column(name = "EMPLOYEE_COUNT", nullable = false)
    @Builder.Default private Integer employeeCount = 0;

    @Column(name = "TOTAL_GROSS", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal totalGross = BigDecimal.ZERO;

    @Column(name = "TOTAL_DEDUCTION", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal totalDeduction = BigDecimal.ZERO;

    @Column(name = "TOTAL_TAX", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal totalTax = BigDecimal.ZERO;

    @Column(name = "TOTAL_NET", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal totalNet = BigDecimal.ZERO;

    @Column(name = "TOTAL_PAID", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal totalPaid = BigDecimal.ZERO;

    @Column(name = "CALCULATED_BY", length = 50) private String calculatedBy;
    @Column(name = "CALCULATED_AT")              private LocalDateTime calculatedAt;
    @Column(name = "REVIEWED_BY", length = 50)   private String reviewedBy;
    @Column(name = "REVIEWED_AT")                private LocalDateTime reviewedAt;
    @Column(name = "APPROVED_BY", length = 50)   private String approvedBy;
    @Column(name = "APPROVED_AT")                private LocalDateTime approvedAt;
    @Column(name = "POSTED_BY", length = 50)     private String postedBy;
    @Column(name = "POSTED_AT")                  private LocalDateTime postedAt;
    @Column(name = "CANCELLED_BY", length = 50)  private String cancelledBy;
    @Column(name = "CANCELLED_AT")               private LocalDateTime cancelledAt;

    @Column(name = "CANCEL_REASON", length = 500) private String cancelReason;
    @Column(name = "REMARKS", length = 1000)      private String remarks;

    @OneToMany(mappedBy = "payroll", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<HrPayrollLine> lines = new ArrayList<>();

    /** Financially immutable — Accounts holds a journal against it. */
    public boolean isFrozen() {
        return HrConstants.PayrollStatus.isFrozen(payrollStatus);
    }

    /** May still be recalculated. */
    public boolean isOpen() {
        return HrConstants.PayrollStatus.isOpen(payrollStatus);
    }

    public boolean isCancelled() {
        return HrConstants.PayrollStatus.CANCELLED.equals(payrollStatus);
    }

    /** What is still owed to staff on this run. */
    @Transient
    public BigDecimal getUnpaid() {
        BigDecimal net  = totalNet  == null ? BigDecimal.ZERO : totalNet;
        BigDecimal paid = totalPaid == null ? BigDecimal.ZERO : totalPaid;
        BigDecimal left = net.subtract(paid);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
