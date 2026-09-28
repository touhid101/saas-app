package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One movement in the provident fund.
 *
 * <h3>Written at POSTED, not at calculation</h3>
 *
 * <p>A payroll run can be recalculated any number of times before it is posted,
 * and a fund whose balance moved on every recalculation would not be a fund.
 * POSTED is where this application already draws the immutability line, so that
 * is where a contribution becomes a fact. {@code uq_hr_pf_ledger_contribution}
 * makes a second contribution for the same run impossible rather than merely
 * unlikely.
 *
 * <p>The payslip still shows the deduction from the moment the run is
 * calculated — it is on {@link HrPayrollLine} like every other figure. This row
 * is the fund's copy, not a second calculation of it.
 *
 * <h3>Rates are copied, not joined</h3>
 *
 * <p>{@link #employeePct} and {@link #employerPct} sit on the row for the same
 * reason a payslip copies its component rates: a statement printed next year
 * must show what was actually contributed, not what the rates happen to be by
 * then.
 *
 * <h3>Amounts are magnitudes; the type carries the sign</h3>
 *
 * <p>Both amount columns are positive and a WITHDRAWAL subtracts. A ledger with
 * negatives in some rows and positives in others is one nobody can total by eye,
 * and {@code ck_hr_pf_amounts} keeps it that way.
 */
@Entity @Table(name = "HR_PF_LEDGER", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrPfLedger extends BaseEntity {

    /** Payroll's own contribution. Never entered by hand. */
    public static final String CONTRIBUTION = "CONTRIBUTION";
    /** Money out, account stays open. */
    public static final String WITHDRAWAL   = "WITHDRAWAL";
    /**
     * Money in that payroll did not put there — an opening balance carried over
     * from a fund kept outside this application, or interest credited to it.
     *
     * <p>Inward only. A correction that has to take money out is a WITHDRAWAL
     * with a remark saying so: one type that could go either way would leave
     * every such row ambiguous until somebody read the remark.
     */
    public static final String ADJUSTMENT   = "ADJUSTMENT";
    /** Money out, account closed. */
    public static final String SETTLEMENT   = "SETTLEMENT";

    /** Closed by ck_hr_pf_entry_type (V82). */
    public static final List<String> ENTRY_TYPES =
            List.of(CONTRIBUTION, WITHDRAWAL, ADJUSTMENT, SETTLEMENT);

    /** The ones a person may record. A contribution comes from posting payroll. */
    public static final List<String> MANUAL_TYPES =
            List.of(WITHDRAWAL, ADJUSTMENT, SETTLEMENT);

    @Id @Column(name = "PID_PF_LEDGER")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_pf_ledger")
    @SequenceGenerator(name = "seq_hr_pf_ledger", sequenceName = "SEQ_HR_PF_LEDGER",
                       allocationSize = 1)
    private Long pidPfLedger;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    @Column(name = "PID_PAYROLL")      private Long pidPayroll;
    @Column(name = "PID_PAYROLL_LINE") private Long pidPayrollLine;
    @Column(name = "PID_PF_SCHEME")    private Long pidPfScheme;

    @Column(name = "ENTRY_DATE", nullable = false) private LocalDate entryDate;
    @Column(name = "PERIOD_FROM") private LocalDate periodFrom;
    @Column(name = "PERIOD_TO")   private LocalDate periodTo;

    @Column(name = "ENTRY_TYPE", length = 20, nullable = false) private String entryType;

    @Column(name = "BASE_AMOUNT", precision = 18, scale = 2)  private BigDecimal baseAmount;
    @Column(name = "EMPLOYEE_PCT", precision = 9, scale = 4)  private BigDecimal employeePct;
    @Column(name = "EMPLOYER_PCT", precision = 9, scale = 4)  private BigDecimal employerPct;

    @Column(name = "EMPLOYEE_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal employeeAmount = BigDecimal.ZERO;

    @Column(name = "EMPLOYER_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal employerAmount = BigDecimal.ZERO;

    @Column(name = "REFERENCE_NO", length = 50) private String referenceNo;
    @Column(name = "REMARKS", length = 1000)    private String remarks;

    @Column(name = "RECORDED_BY", length = 50) private String recordedBy;
    @Column(name = "RECORDED_AT")              private LocalDateTime recordedAt;

    public boolean isActive() { return "Y".equals(getStatus()); }

    /** True for the two types that take money out of the fund. */
    @Transient
    public boolean isOutward() {
        return WITHDRAWAL.equals(entryType) || SETTLEMENT.equals(entryType);
    }

    /** What this row does to the balance: positive in, negative out. */
    @Transient
    public BigDecimal signedTotal() {
        BigDecimal total = nz(employeeAmount).add(nz(employerAmount));
        return isOutward() ? total.negate() : total;
    }

    @Transient
    public BigDecimal signedEmployee() {
        return isOutward() ? nz(employeeAmount).negate() : nz(employeeAmount);
    }

    @Transient
    public BigDecimal signedEmployer() {
        return isOutward() ? nz(employerAmount).negate() : nz(employerAmount);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
