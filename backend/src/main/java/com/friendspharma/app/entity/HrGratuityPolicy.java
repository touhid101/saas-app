package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A branch's gratuity formula.
 *
 * <p><b>Every figure here is a question this application cannot answer.</b>
 * Days of pay per year of service, the wage they are valued on, the divisor
 * that turns a monthly wage into a daily one, the qualifying period and what a
 * part-year is worth are all policy and in places statute — P-08 in
 * {@code 01-hr-gap-analysis.md}. They are columns so that somebody who can
 * answer them has somewhere to put the answer.
 *
 * <p>A null {@link #daysPerYear} means nobody has decided, and no gratuity is
 * computed for anybody at that branch. That is the shipped state.
 *
 * <p>{@link #daysInMonth} deserves its own mention: 26 working days and 30
 * calendar days give materially different answers on the same salary, and which
 * one applies is a matter of contract or law rather than arithmetic. It is
 * entered, never assumed.
 *
 * <p>Effective-dated per branch like {@link HrWorkingPolicy} and
 * {@link HrPfScheme}. Superseding closes a policy rather than rewriting it,
 * because an award already approved was approved under the old one.
 */
@Entity @Table(name = "HR_GRATUITY_POLICY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrGratuityPolicy extends BaseEntity {

    public static final String ON_BASIC = "BASIC";
    public static final String ON_GROSS = "GROSS";
    public static final List<String> BASES = List.of(ON_BASIC, ON_GROSS);

    /** Only whole completed years count. */
    public static final String IGNORE   = "IGNORE";
    /** A part year counts as its fraction. */
    public static final String PRO_RATA = "PRO_RATA";
    /** A part year counts as a whole one. */
    public static final String ROUND_UP = "ROUND_UP";
    public static final List<String> PARTIAL_RULES = List.of(IGNORE, PRO_RATA, ROUND_UP);

    /** Days in a year, for turning a service span into years. */
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    @Id @Column(name = "PID_GRATUITY_POLICY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_gratuity_policy")
    @SequenceGenerator(name = "seq_hr_gratuity_policy", sequenceName = "SEQ_HR_GRATUITY_POLICY",
                       allocationSize = 1)
    private Long pidGratuityPolicy;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;

    @Column(name = "POLICY_NAME", length = 120, nullable = false) private String policyName;

    /** Null means undecided, and nothing is computed. See the class comment. */
    @Column(name = "DAYS_PER_YEAR", precision = 6, scale = 2) private BigDecimal daysPerYear;
    @Column(name = "DAYS_IN_MONTH", precision = 5, scale = 2) private BigDecimal daysInMonth;

    @Column(name = "WAGE_BASIS", length = 10, nullable = false)
    @Builder.Default private String wageBasis = ON_BASIC;

    /** Below this, nothing is payable. Null means no qualifying period. */
    @Column(name = "MIN_SERVICE_YEARS", precision = 5, scale = 2) private BigDecimal minServiceYears;
    /** Service beyond this is not counted. Null means no cap. */
    @Column(name = "MAX_SERVICE_YEARS", precision = 5, scale = 2) private BigDecimal maxServiceYears;

    @Column(name = "PARTIAL_YEAR_RULE", length = 20, nullable = false)
    @Builder.Default private String partialYearRule = IGNORE;

    @Column(name = "EFFECTIVE_FROM", nullable = false) private LocalDate effectiveFrom;
    @Column(name = "EFFECTIVE_TO")                     private LocalDate effectiveTo;

    @Column(name = "REMARKS", length = 1000) private String remarks;

    @Transient
    public boolean isCurrent() { return effectiveTo == null; }

    public boolean isActive() { return "Y".equals(getStatus()); }

    /**
     * Whether this policy can actually produce a figure.
     *
     * <p>A policy row with no days per year and no divisor is a placeholder
     * somebody started and did not finish. The screen says so; the calculation
     * refuses rather than treating the blanks as zero.
     */
    @Transient
    public boolean isComplete() {
        return daysPerYear != null && daysPerYear.signum() > 0
            && daysInMonth != null && daysInMonth.signum() > 0;
    }

    /* ══ The arithmetic, in one place ══════════════════════════════════════ */

    /**
     * Raw service in years between two dates, before any rule is applied.
     *
     * <p>Both ends inclusive, because somebody's last working day is a day they
     * worked. 365.25 rather than 365 so that four years of service across a leap
     * year does not read as four years and a day.
     */
    public static BigDecimal rawYears(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) return BigDecimal.ZERO;
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return BigDecimal.valueOf(days).divide(DAYS_PER_YEAR, 4, RoundingMode.HALF_UP);
    }

    /**
     * Countable service, after the part-year rule and the cap.
     *
     * <p>Applied in that order deliberately: rounding a part year up and then
     * capping is not the same as capping and then rounding, and the cap is a
     * ceiling on what is paid rather than on what was served.
     */
    public BigDecimal countableYears(BigDecimal raw) {
        if (raw == null || raw.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal years = switch (partialYearRule == null ? IGNORE : partialYearRule) {
            case PRO_RATA -> raw;
            case ROUND_UP -> raw.setScale(0, RoundingMode.CEILING);
            default       -> raw.setScale(0, RoundingMode.FLOOR);
        };
        if (maxServiceYears != null && maxServiceYears.signum() > 0
                && years.compareTo(maxServiceYears) > 0) {
            years = maxServiceYears;
        }
        return years.setScale(4, RoundingMode.HALF_UP);
    }

    /** Whether somebody has served long enough to be owed anything. */
    public boolean qualifies(BigDecimal rawYears) {
        if (minServiceYears == null || minServiceYears.signum() <= 0) return true;
        return rawYears != null && rawYears.compareTo(minServiceYears) >= 0;
    }

    /**
     * The daily wage a gratuity is paid at.
     *
     * <p>Four decimal places, not two: the daily rate is an intermediate figure
     * multiplied by tens or hundreds of days, and rounding it to the paisa first
     * moves the answer by more than the rounding it saves.
     */
    public BigDecimal dailyWage(BigDecimal monthlyWage) {
        if (monthlyWage == null || monthlyWage.signum() <= 0) return BigDecimal.ZERO;
        if (daysInMonth == null || daysInMonth.signum() <= 0) return BigDecimal.ZERO;
        return monthlyWage.divide(daysInMonth, 4, RoundingMode.HALF_UP);
    }
}
