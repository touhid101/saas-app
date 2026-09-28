package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A pay month for one branch.
 *
 * <p>Named by year and month, but carrying explicit from and to dates, because
 * a pharmacy whose pay month runs the 26th to the 25th is not unusual and the
 * dates are what attendance is counted between.
 *
 * <p><b>This is HR's period and is not the accounting period.</b> The two are
 * related — posting a payroll into a closed accounting period is refused — but
 * they are opened and closed by different people for different reasons, and one
 * table serving both would tie a branch manager's payroll to the accountant's
 * year end.
 *
 * <p>{@link #workingDays} is entered once and stored, because payroll divides
 * by it to value an absence. Recomputing it from a calendar whose holidays have
 * since been edited would change a month that is closed.
 */
@Entity @Table(name = "HR_PAYROLL_PERIOD", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrPayrollPeriod extends BaseEntity {

    public static final String OPEN   = "OPEN";
    public static final String CLOSED = "CLOSED";

    @Id @Column(name = "PID_PERIOD")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_period")
    @SequenceGenerator(name = "seq_hr_period", sequenceName = "SEQ_HR_PAYROLL_PERIOD",
                       allocationSize = 1)
    private Long pidPeriod;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;

    @Column(name = "PERIOD_YEAR", nullable = false)  private Integer periodYear;
    @Column(name = "PERIOD_MONTH", nullable = false) private Integer periodMonth;
    @Column(name = "PERIOD_FROM", nullable = false)  private LocalDate periodFrom;
    @Column(name = "PERIOD_TO", nullable = false)    private LocalDate periodTo;

    @Column(name = "PERIOD_STATUS", length = 20, nullable = false)
    @Builder.Default private String periodStatus = OPEN;

    @Column(name = "WORKING_DAYS", precision = 5, scale = 2)
    private BigDecimal workingDays;

    @Column(name = "REMARKS", length = 500) private String remarks;

    public boolean isOpen()   { return OPEN.equals(periodStatus); }
    public boolean isActive() { return "Y".equals(getStatus()); }

    /** "September 2026", for messages and headings. */
    @Transient
    public String getLabel() {
        if (periodYear == null || periodMonth == null) return "";
        return java.time.Month.of(periodMonth)
                .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                + " " + periodYear;
    }
}
