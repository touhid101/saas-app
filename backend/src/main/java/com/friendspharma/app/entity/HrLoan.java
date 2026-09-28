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
 * A sum lent to an employee against a repayment plan.
 *
 * <p><b>Interest is not implemented.</b> {@link #interestRate} exists, is
 * nullable, and nothing computes with it. Whether this pharmacy charges
 * interest on a staff loan, at what rate, and whether doing so is permissible,
 * is a policy and possibly a legal question — recorded in
 * {@code 02-hr-module-srs.md} as BUSINESS POLICY CONFIRMATION REQUIRED. A zero
 * default would look like an answer, and a guessed rate would be one.
 *
 * <p>The schedule is written when the loan is approved, not built lazily as
 * payroll runs, so the employee can be shown the whole plan before accepting
 * it. See {@link HrLoanSchedule}.
 */
@Entity @Table(name = "HR_LOAN", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrLoan extends BaseEntity {

    @Id @Column(name = "PID_LOAN")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_loan")
    @SequenceGenerator(name = "seq_hr_loan", sequenceName = "SEQ_HR_LOAN", allocationSize = 1)
    private Long pidLoan;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    @Column(name = "LOAN_NO", length = 30)           private String loanNo;
    @Column(name = "REQUEST_DATE", nullable = false) private LocalDate requestDate;

    @Column(name = "PRINCIPAL", precision = 18, scale = 2, nullable = false)
    private BigDecimal principal;

    @Column(name = "APPROVED_PRINCIPAL", precision = 18, scale = 2)
    private BigDecimal approvedPrincipal;

    @Column(name = "INSTALLMENTS", nullable = false)
    @Builder.Default private Integer installments = 1;

    @Column(name = "INSTALLMENT_AMOUNT", precision = 18, scale = 2)
    private BigDecimal installmentAmount;

    /** Present, unused, undecided — see the class comment. */
    @Column(name = "INTEREST_RATE", precision = 9, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "RECOVERED_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal recoveredAmount = BigDecimal.ZERO;

    @Column(name = "START_DATE") private LocalDate startDate;
    @Column(name = "PURPOSE", length = 1000) private String purpose;

    /** See {@link HrConstants.LoanStatus}. */
    @Column(name = "LOAN_STATUS", length = 20, nullable = false)
    @Builder.Default private String loanStatus = HrConstants.LoanStatus.DRAFT;

    @Column(name = "DECIDED_BY", length = 50) private String decidedBy;
    @Column(name = "DECIDED_AT")              private LocalDateTime decidedAt;
    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;

    @Column(name = "DISBURSED_DATE")                 private LocalDate disbursedDate;
    @Column(name = "PAYMENT_MODE", length = 20)      private String paymentMode;
    @Column(name = "PAYMENT_REFERENCE", length = 60) private String paymentReference;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("installmentNo ASC")
    @Builder.Default
    private List<HrLoanSchedule> schedule = new ArrayList<>();

    @Transient
    public BigDecimal getOutstanding() {
        BigDecimal approved = approvedPrincipal == null ? BigDecimal.ZERO : approvedPrincipal;
        BigDecimal back     = recoveredAmount   == null ? BigDecimal.ZERO : recoveredAmount;
        BigDecimal left     = approved.subtract(back);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    public boolean isRecoverable() {
        return HrConstants.LoanStatus.RECOVERABLE.contains(loanStatus)
                && getOutstanding().signum() > 0;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
