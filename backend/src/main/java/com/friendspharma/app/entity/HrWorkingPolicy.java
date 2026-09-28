package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which days a branch works, and what counts as late.
 *
 * <p><b>This is the answer to P-04</b>, the open question recorded in
 * {@code 02-hr-module-srs.md}: the application held no weekly-off pattern, so
 * leave counted every calendar day and payroll had no way to tell a Friday from
 * a Tuesday.
 *
 * <p><b>It does not decide what a weekend is.</b> {@link #weekendDays} has no
 * default. A Friday-Saturday weekend, a Sunday one, and a shop that opens seven
 * days are all normal here, and a seeded value would have been read as the
 * answer. <b>A branch with no policy, or a policy naming no weekend, behaves
 * exactly as the application did before this table existed</b> — every day is a
 * working day. That is deliberate: adding the table changes nothing until
 * somebody decides something.
 *
 * <p>Effective-dated rather than edited in place. Changing a policy would
 * otherwise rewrite what last month's attendance was judged against.
 */
@Entity @Table(name = "HR_WORKING_POLICY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrWorkingPolicy extends BaseEntity {

    @Id @Column(name = "PID_POLICY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_policy")
    @SequenceGenerator(name = "seq_hr_policy", sequenceName = "SEQ_HR_WORKING_POLICY",
                       allocationSize = 1)
    private Long pidPolicy;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;

    @Column(name = "POLICY_NAME", length = 120, nullable = false) private String policyName;
    @Column(name = "PID_DEFAULT_SHIFT")                           private Long pidDefaultShift;

    /**
     * ISO day numbers, comma separated. 1 = Monday through 7 = Sunday, matching
     * {@link DayOfWeek#getValue()} so no translation table is needed anywhere.
     *
     * <p>Null or empty means no weekly off has been decided.
     */
    @Column(name = "WEEKEND_DAYS", length = 20) private String weekendDays;

    @Column(name = "LATE_THRESHOLD_MINUTES", nullable = false)
    @Builder.Default private Integer lateThresholdMinutes = 0;

    @Column(name = "EARLY_LEAVE_THRESHOLD_MINUTES", nullable = false)
    @Builder.Default private Integer earlyLeaveThresholdMinutes = 0;

    /** Below this a present day is half a day. Null disables the rule. */
    @Column(name = "HALF_DAY_MINUTES")    private Integer halfDayMinutes;
    /** Below this a day is not counted as worked at all. Null disables it. */
    @Column(name = "MIN_WORKING_MINUTES") private Integer minWorkingMinutes;

    /**
     * Whether overtime may be claimed here — not what it is worth.
     *
     * <p>The rate lives on the salary structure. This says whether the hours may
     * be recorded against a claim at all, which is a different decision and
     * often a different person's.
     */
    @Column(name = "OVERTIME_ALLOWED", nullable = false)
    @Builder.Default private Integer overtimeAllowed = 0;

    @Column(name = "OVERTIME_MIN_MINUTES", nullable = false)
    @Builder.Default private Integer overtimeMinMinutes = 0;

    @Column(name = "EFFECTIVE_FROM", nullable = false) private LocalDate effectiveFrom;
    @Column(name = "EFFECTIVE_TO")                     private LocalDate effectiveTo;

    /* ── Reading the weekend ─────────────────────────────────────────────── */

    /** The weekend as day-of-week values. Empty where none has been set. */
    @Transient
    public Set<DayOfWeek> weekend() {
        return parseWeekend(weekendDays);
    }

    /**
     * Parses "5,6" into Friday and Saturday.
     *
     * <p>Tolerant of stray spaces and empty entries, and silently drops anything
     * outside 1–7 — the CHECK constraint already refuses those on write, so a
     * bad value here means the row predates the constraint and the right
     * response is to ignore it rather than to fail a payroll run.
     */
    public static Set<DayOfWeek> parseWeekend(String raw) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                int n = Integer.parseInt(t);
                if (n >= 1 && n <= 7) out.add(DayOfWeek.of(n));
            } catch (NumberFormatException ignored) {
                // See the comment above: a malformed entry is not worth a failure.
            }
        }
        return out;
    }

    /** "5,6" from a set, for writing back. */
    public static String formatWeekend(Set<DayOfWeek> days) {
        if (days == null || days.isEmpty()) return null;
        return days.stream().sorted()
                .map(d -> String.valueOf(d.getValue()))
                .reduce((a, b) -> a + "," + b).orElse(null);
    }

    public boolean isWeekend(LocalDate date) {
        return date != null && weekend().contains(date.getDayOfWeek());
    }

    /** True where nobody has decided a weekly off — the shipped state. */
    public boolean hasNoWeekend() {
        return weekend().isEmpty();
    }

    public boolean allowsOvertime() {
        return overtimeAllowed != null && overtimeAllowed == 1;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }

    /** Whether this policy governs a given date. */
    public boolean appliesOn(LocalDate date) {
        if (date == null || !isActive()) return false;
        if (effectiveFrom != null && date.isBefore(effectiveFrom)) return false;
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }
}
