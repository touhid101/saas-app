package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * A specific date the branch does not work.
 *
 * <p><b>{@link #pidBranch} is nullable and that is the point.</b> A national
 * holiday belongs to the organisation and applies everywhere; a branch closing
 * for a local occasion applies to one shop. One table with a nullable branch
 * answers both, where two tables would eventually disagree about a date that
 * appeared in each.
 *
 * <p><b>No government holidays are seeded.</b> They change every year, they are
 * declared by gazette rather than derived by formula, and a list invented here
 * would be wrong by next year and trusted anyway.
 */
@Entity @Table(name = "HR_HOLIDAY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrHoliday extends BaseEntity {

    public static final String GOVERNMENT = "GOVERNMENT";
    public static final String PUBLIC     = "PUBLIC";
    public static final String COMPANY    = "COMPANY";
    public static final String SPECIAL    = "SPECIAL";
    public static final String RELIGIOUS  = "RELIGIOUS";

    /** Closed by ck_hr_holiday_type (V79). */
    public static final List<String> TYPES =
            List.of(GOVERNMENT, PUBLIC, COMPANY, SPECIAL, RELIGIOUS);

    @Id @Column(name = "PID_HOLIDAY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_holiday")
    @SequenceGenerator(name = "seq_hr_holiday", sequenceName = "SEQ_HR_HOLIDAY", allocationSize = 1)
    private Long pidHoliday;

    @Column(name = "PID_ORG", nullable = false) private Long pidOrg;
    @Column(name = "PID_COMPANY")               private Long pidCompany;

    /** Null means the whole organisation. */
    @Column(name = "PID_BRANCH") private Long pidBranch;

    @Column(name = "HOLIDAY_DATE", nullable = false)          private LocalDate holidayDate;
    @Column(name = "HOLIDAY_NAME", length = 150, nullable = false) private String holidayName;

    @Column(name = "HOLIDAY_TYPE", length = 20, nullable = false)
    @Builder.Default private String holidayType = COMPANY;

    /**
     * Kept in step with the date by {@code ck_hr_holiday_year}.
     *
     * <p>Stored rather than derived so a year filter can use an index; the
     * constraint is what stops the two drifting, which would silently lose a
     * December holiday from a year listing.
     */
    @Column(name = "HOLIDAY_YEAR", nullable = false) private Integer holidayYear;

    /**
     * An unpaid holiday is a closure the employer does not pay for.
     *
     * <p>Rare, and not the same thing as a weekend — a weekend is simply not a
     * working day, while an unpaid holiday is a working day the shop chose to
     * close.
     */
    @Column(name = "IS_PAID", nullable = false)
    @Builder.Default private Integer isPaid = 1;

    @Column(name = "DESCRIPTION", length = 500) private String description;

    public boolean isPaidHoliday()  { return isPaid != null && isPaid == 1; }
    public boolean isOrgWide()      { return pidBranch == null; }
    public boolean isActive()       { return "Y".equals(getStatus()); }

    /** Whether this holiday applies to a given branch. */
    public boolean appliesTo(Long branchId) {
        return isOrgWide() || (branchId != null && branchId.equals(pidBranch));
    }
}
