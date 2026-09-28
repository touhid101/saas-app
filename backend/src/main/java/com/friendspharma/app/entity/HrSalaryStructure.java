package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a payslip, not the money on it.
 *
 * <p>A structure says "basic, plus 40% house rent, plus a fixed transport
 * allowance, less provident fund at 5%". What anybody actually earns is
 * {@code HR_EMPLOYEES.BASIC_SALARY}. One structure therefore serves every
 * employee on the same terms, and a raise is one number on one person rather
 * than a structure of their own.
 *
 * <p>Branch-scoped, like every other master in this product. There is no
 * cross-branch screen, so an organisation-wide structure would be invisible to
 * the screen that maintains it.
 */
@Entity @Table(name = "HR_SALARY_STRUCTURE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrSalaryStructure extends BaseEntity {

    @Id @Column(name = "PID_STRUCTURE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_structure")
    @SequenceGenerator(name = "seq_hr_structure", sequenceName = "SEQ_HR_SALARY_STRUCTURE",
                       allocationSize = 1)
    private Long pidStructure;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "STRUCTURE_NAME", length = 120, nullable = false)
    private String structureName;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;

    @Column(name = "EFFECTIVE_FROM")
    private LocalDate effectiveFrom;

    @Column(name = "EFFECTIVE_TO")
    private LocalDate effectiveTo;

    /* ── Overtime and absence policy ──────────────────────────────────────
     *
     * Two numbers this module refuses to guess.
     *
     * A null {@link #overtimeMultiplier} means overtime is recorded but not
     * paid, and the payslip says so. A rate is a policy question and in places
     * a statutory one; a plausible 1.5 defaulted here would read as the answer,
     * and the first anyone would learn otherwise is a wage claim.
     */

    /** Needed to turn a monthly basic into an hourly rate. */
    @Column(name = "STANDARD_HOURS_PER_DAY", precision = 5, scale = 2)
    private BigDecimal standardHoursPerDay;

    /** Null means overtime is unpaid at this pharmacy. */
    @Column(name = "OVERTIME_MULTIPLIER", precision = 9, scale = 4)
    private BigDecimal overtimeMultiplier;

    /**
     * What an unpaid day costs: {@code BASIC} or {@code GROSS}.
     *
     * <p>BASIC is the default because it is the narrower of the two, and
     * deducting on gross without having been asked to takes more money than
     * deducting on basic.
     */
    @Column(name = "ABSENCE_BASIS", length = 10)
    @Builder.Default
    private String absenceBasis = ABSENCE_ON_BASIC;

    public static final String ABSENCE_ON_BASIC = "BASIC";
    public static final String ABSENCE_ON_GROSS = "GROSS";

    /** Whether overtime is valued at all under this structure. */
    public boolean paysOvertime() {
        return overtimeMultiplier != null
                && overtimeMultiplier.signum() > 0
                && standardHoursPerDay != null
                && standardHoursPerDay.signum() > 0;
    }

    /**
     * Loaded with the structure, because a structure is never useful without
     * its lines — every read of one is a read of all of them, and a lazy
     * collection here would be a second query on every single access.
     *
     * <p>Ordered by SORT_ORDER because the order is arithmetic, not
     * presentation: a PCT_OF_GROSS deduction computed before the allowances
     * that make up that gross produces a different, wrong number.
     */
    @OneToMany(mappedBy = "structure", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, pidComponent ASC")
    @Builder.Default
    private List<HrSalaryComponent> components = new ArrayList<>();

    public boolean isActive() {
        return "Y".equals(getStatus());
    }

    /** Whether the structure may be used to value a payslip on this date. */
    public boolean isEffectiveOn(LocalDate date) {
        if (date == null) return isActive();
        if (effectiveFrom != null && date.isBefore(effectiveFrom)) return false;
        if (effectiveTo   != null && date.isAfter(effectiveTo))    return false;
        return isActive();
    }
}
