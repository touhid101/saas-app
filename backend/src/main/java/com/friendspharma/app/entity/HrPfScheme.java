package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A branch's provident fund terms.
 *
 * <p><b>Nothing here has a default rate, and that is the point.</b> Employee
 * share, employer share, the wage they apply to and any ceiling are policy —
 * in places statutory policy — and this application does not know them. P-07 in
 * {@code 01-hr-gap-analysis.md} says so. Seeding 8% because it is common would
 * put a figure on a payslip that no accountant chose, and it would be believed.
 *
 * <p>So a null rate means <b>nobody has decided</b>, not nought per cent. With
 * no scheme at a branch, payroll deducts no PF and behaves exactly as it did
 * before this table existed. Same contract as the overtime multiplier on
 * {@link HrSalaryStructure} and the annual quota on {@link HrLeaveType}.
 *
 * <p>Effective-dated per branch like {@link HrWorkingPolicy}: last month's
 * payroll was computed against last month's rates, so superseding a scheme
 * closes it rather than rewriting it, and {@code uq_hr_pf_scheme_current} keeps
 * "the scheme in force" a question with one answer.
 */
@Entity @Table(name = "HR_PF_SCHEME", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrPfScheme extends BaseEntity {

    /** Closed by ck_hr_pf_base (V82). */
    public static final String ON_BASIC = "BASIC";
    public static final String ON_GROSS = "GROSS";
    public static final List<String> BASES = List.of(ON_BASIC, ON_GROSS);

    @Id @Column(name = "PID_PF_SCHEME")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_pf_scheme")
    @SequenceGenerator(name = "seq_hr_pf_scheme", sequenceName = "SEQ_HR_PF_SCHEME",
                       allocationSize = 1)
    private Long pidPfScheme;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;

    @Column(name = "SCHEME_NAME", length = 120, nullable = false) private String schemeName;

    /** Null means undecided. See the class comment. */
    @Column(name = "EMPLOYEE_PCT", precision = 9, scale = 4) private BigDecimal employeePct;
    @Column(name = "EMPLOYER_PCT", precision = 9, scale = 4) private BigDecimal employerPct;

    @Column(name = "CONTRIBUTION_BASE", length = 10, nullable = false)
    @Builder.Default private String contributionBase = ON_BASIC;

    /** The most wage a contribution is taken on. Null means no ceiling. */
    @Column(name = "WAGE_CEILING", precision = 18, scale = 2) private BigDecimal wageCeiling;

    /** Null or zero means somebody contributes from their first payslip. */
    @Column(name = "MIN_SERVICE_MONTHS") private Integer minServiceMonths;

    @Column(name = "EFFECTIVE_FROM", nullable = false) private LocalDate effectiveFrom;
    @Column(name = "EFFECTIVE_TO")                     private LocalDate effectiveTo;

    @Column(name = "REMARKS", length = 1000) private String remarks;

    /** The one still open. A closed scheme governed the months it was open for. */
    @Transient
    public boolean isCurrent() { return effectiveTo == null; }

    public boolean isActive() { return "Y".equals(getStatus()); }

    /**
     * Whether this scheme actually takes a contribution from anybody.
     *
     * <p>False for a scheme created with no rates on it — which is a legitimate
     * state, and the screen says so rather than pretending the fund is running.
     */
    @Transient
    public boolean pays() {
        return (employeePct != null && employeePct.signum() > 0)
            || (employerPct != null && employerPct.signum() > 0);
    }

    /** True when both rates are still unanswered — the shipped state. */
    @Transient
    public boolean isRateSet() {
        return employeePct != null || employerPct != null;
    }
}
