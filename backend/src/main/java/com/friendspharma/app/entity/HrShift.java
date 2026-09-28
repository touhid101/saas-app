package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * When a working day starts and ends.
 *
 * <p><b>An overnight shift is not an error.</b> {@link #endTime} may be earlier
 * than {@link #startTime} — 22:00 to 06:00 ends on the following date — and
 * {@link #crossesMidnight} records that rather than leaving every reader to
 * infer it. Storing the conclusion means the rule is stated once; inferring it
 * in five places is five chances for one of them to be wrong, and the one that
 * is wrong pays somebody for eight hours less than they worked.
 *
 * <p>{@link #workingMinutes} is stored for the same reason the payroll period
 * stores its working-day count: a shift edited next year must not change what
 * last year's attendance was measured against.
 */
@Entity @Table(name = "HR_SHIFT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrShift extends BaseEntity {

    @Id @Column(name = "PID_SHIFT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_shift")
    @SequenceGenerator(name = "seq_hr_shift", sequenceName = "SEQ_HR_SHIFT", allocationSize = 1)
    private Long pidShift;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;

    @Column(name = "SHIFT_CODE", length = 20, nullable = false)  private String shiftCode;
    @Column(name = "SHIFT_NAME", length = 100, nullable = false) private String shiftName;

    @Column(name = "START_TIME", nullable = false) private LocalTime startTime;
    @Column(name = "END_TIME", nullable = false)   private LocalTime endTime;

    @Column(name = "CROSSES_MIDNIGHT", nullable = false)
    @Builder.Default private Integer crossesMidnight = 0;

    @Column(name = "BREAK_MINUTES", nullable = false) @Builder.Default private Integer breakMinutes = 0;

    /** Minutes after the start that are still not late. */
    @Column(name = "GRACE_MINUTES", nullable = false) @Builder.Default private Integer graceMinutes = 0;

    @Column(name = "WORKING_MINUTES") private Integer workingMinutes;

    public boolean isOvernight() {
        return crossesMidnight != null && crossesMidnight == 1;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }

    /**
     * Whether this shift spans midnight, worked out from the two times.
     *
     * <p>The single place that decision is made. {@code CROSSES_MIDNIGHT} is
     * written from here on save, so the column and the times cannot disagree.
     */
    public static boolean spansMidnight(LocalTime start, LocalTime end) {
        return start != null && end != null && !end.isAfter(start);
    }

    /**
     * Gross span less the break — what a full day of this shift is worth.
     *
     * <p>An overnight shift is the day's remainder plus the next morning:
     * 22:00 to 06:00 is 120 minutes to midnight plus 360 after it, not the
     * negative sixteen hours a plain subtraction gives.
     */
    public static int netMinutes(LocalTime start, LocalTime end, int breakMinutes) {
        if (start == null || end == null) return 0;
        long gross = spansMidnight(start, end)
                ? (24 * 60 - start.toSecondOfDay() / 60) + (end.toSecondOfDay() / 60)
                : Duration.between(start, end).toMinutes();
        return (int) Math.max(0, gross - Math.max(0, breakMinutes));
    }

    /**
     * When this shift is scheduled to start and finish on a given date.
     *
     * <p>The end lands on the following date for an overnight shift, which is
     * the whole reason the flag exists — comparing a 06:00 finish against a
     * 22:00 start on the same date makes every night shift look like sixteen
     * hours of absence.
     */
    public LocalDateTime scheduledIn(LocalDate date) {
        return date == null || startTime == null ? null : date.atTime(startTime);
    }

    public LocalDateTime scheduledOut(LocalDate date) {
        if (date == null || endTime == null) return null;
        return isOvernight() ? date.plusDays(1).atTime(endTime) : date.atTime(endTime);
    }

    /** Minutes late, after the grace period. Never negative. */
    public int lateMinutes(LocalDateTime actualIn, LocalDate date) {
        LocalDateTime due = scheduledIn(date);
        if (actualIn == null || due == null) return 0;
        long over = Duration.between(due.plusMinutes(nz(graceMinutes)), actualIn).toMinutes();
        return (int) Math.max(0, over);
    }

    /** Minutes left early. Never negative. */
    public int earlyLeaveMinutes(LocalDateTime actualOut, LocalDate date) {
        LocalDateTime due = scheduledOut(date);
        if (actualOut == null || due == null) return 0;
        long short_ = Duration.between(actualOut, due).toMinutes();
        return (int) Math.max(0, short_);
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
