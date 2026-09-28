package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What somebody spent on the pharmacy's behalf, and getting it back.
 *
 * <h3>A reimbursement is not pay</h3>
 *
 * <p>Somebody who takes a rickshaw to the wholesaler and is given the fare back
 * has not earned anything — the money is theirs being returned. So an approved
 * claim is <b>paid on its own</b>, with a date, a mode and a reference, exactly
 * as an advance disbursement is. It never reaches a payslip.
 *
 * <p>That is not squeamishness about categories. If a reimbursement were added
 * to {@code GROSS_SALARY} it would sit inside the taxable base, and — since V82
 * — inside the provident fund base whenever a scheme is taken on gross. The
 * pharmacy would deduct PF from a rickshaw fare and hand back less than was
 * spent.
 *
 * <p>Deliberately not the shape {@link HrOvertimeRequest} took. Overtime is
 * work, so it belongs on the payslip and the claim feeds the attendance register
 * payroll already reads. A reimbursement is not work, so it sits beside the
 * payslip and nothing about payroll changes.
 *
 * <h3>Header and lines</h3>
 *
 * <p>A week of conveyance is six journeys on six dates. The totals live here and
 * the detail lives on {@link HrExpenseClaimLine}; an approver who allows the
 * fares and refuses the dinner has made two decisions, and both are visible on
 * the lines they belong to.
 */
@Entity @Table(name = "HR_EXPENSE_CLAIM", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrExpenseClaim extends BaseEntity {

    public static final String DRAFT     = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String PAID      = "PAID";
    public static final String CANCELLED = "CANCELLED";

    /** Closed by ck_hr_expense_claim_status (V83). */
    public static final List<String> ALL_STATUSES =
            List.of(DRAFT, SUBMITTED, APPROVED, REJECTED, PAID, CANCELLED);

    @Id @Column(name = "PID_EXPENSE_CLAIM")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_expense_claim")
    @SequenceGenerator(name = "seq_hr_expense_claim", sequenceName = "SEQ_HR_EXPENSE_CLAIM",
                       allocationSize = 1)
    private Long pidExpenseClaim;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    @Column(name = "CLAIM_NO", length = 30)        private String claimNo;
    @Column(name = "CLAIM_DATE", nullable = false) private LocalDate claimDate;

    /** The span the lines cover. Maintained from the lines, never entered. */
    @Column(name = "PERIOD_FROM") private LocalDate periodFrom;
    @Column(name = "PERIOD_TO")   private LocalDate periodTo;

    @Column(name = "CLAIMED_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal claimedAmount = BigDecimal.ZERO;

    /** Null until decided. Never more than was claimed — ck_hr_expense_claim_approved. */
    @Column(name = "APPROVED_AMOUNT", precision = 18, scale = 2) private BigDecimal approvedAmount;

    @Column(name = "PURPOSE", length = 1000) private String purpose;

    @Column(name = "CLAIM_STATUS", length = 20, nullable = false)
    @Builder.Default private String claimStatus = DRAFT;

    @Column(name = "SUBMITTED_BY", length = 50) private String submittedBy;
    @Column(name = "SUBMITTED_AT")              private LocalDateTime submittedAt;
    @Column(name = "DECIDED_BY", length = 50)   private String decidedBy;
    @Column(name = "DECIDED_AT")                private LocalDateTime decidedAt;
    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;

    @Column(name = "PAID_DATE")                      private LocalDate paidDate;
    @Column(name = "PAYMENT_MODE", length = 20)      private String paymentMode;
    @Column(name = "PAYMENT_REFERENCE", length = 60) private String paymentReference;
    @Column(name = "PAID_BY", length = 50)           private String paidBy;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, pidExpenseLine ASC")
    @Builder.Default
    private List<HrExpenseClaimLine> lines = new ArrayList<>();

    public boolean isDraft()     { return DRAFT.equals(claimStatus); }
    public boolean isSubmitted() { return SUBMITTED.equals(claimStatus); }
    public boolean isApproved()  { return APPROVED.equals(claimStatus); }
    public boolean isPaid()      { return PAID.equals(claimStatus); }
    public boolean isActive()    { return "Y".equals(getStatus()); }

    /** Only a draft may be changed. A submitted claim is cancelled and refiled. */
    @Transient
    public boolean isEditable() { return isDraft(); }
}
