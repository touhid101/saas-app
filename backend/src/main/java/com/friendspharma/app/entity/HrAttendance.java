package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One employee, one day.
 *
 * <p><b>Minutes, never hours.</b> Every duration on this row is an integer
 * number of minutes. Hours as a decimal loses a minute to rounding on almost
 * every row, and overtime paid on a rounded hour is a complaint waiting to
 * happen. The screen shows hours; the table holds minutes; {@link #hours} is
 * the one place the conversion is done.
 *
 * <p><b>Check-in and check-out are timestamps, not times of day.</b> A night
 * shift ends on the following date and a time-of-day column cannot say so.
 *
 * <p><b>The duplicate rule lives in the database.</b> {@code uq_hr_attendance_day}
 * refuses a second live row for an employee on a date. The service checks first
 * so the user gets a sentence rather than a constraint name, but two devices
 * posting the same punch at the same moment both pass a service check — the
 * index is what makes the rule true.
 */
@Entity @Table(name = "HR_ATTENDANCE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrAttendance extends BaseEntity {

    @Id @Column(name = "PID_ATTENDANCE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_attendance")
    @SequenceGenerator(name = "seq_hr_attendance", sequenceName = "SEQ_HR_ATTENDANCE",
                       allocationSize = 1)
    private Long pidAttendance;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_EMPLOYEE", nullable = false)
    private Long pidEmployee;

    @Column(name = "ATTENDANCE_DATE", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "CHECK_IN")
    private LocalDateTime checkIn;

    @Column(name = "CHECK_OUT")
    private LocalDateTime checkOut;

    @Column(name = "WORKING_MINUTES")     @Builder.Default private Integer workingMinutes    = 0;
    @Column(name = "LATE_MINUTES")        @Builder.Default private Integer lateMinutes       = 0;
    @Column(name = "EARLY_LEAVE_MINUTES") @Builder.Default private Integer earlyLeaveMinutes = 0;
    @Column(name = "OVERTIME_MINUTES")    @Builder.Default private Integer overtimeMinutes   = 0;

    /** See {@link HrConstants.AttendanceStatus}. */
    @Column(name = "ATTENDANCE_STATUS", length = 20, nullable = false)
    private String attendanceStatus;

    /**
     * Where the row came from. Only {@code MANUAL} may be edited afterwards —
     * a punch from a device is evidence, and correcting it means overriding it
     * with a manual row, not quietly rewriting what the device recorded.
     */
    @Column(name = "SOURCE", length = 20)
    @Builder.Default
    private String source = HrConstants.AttendanceSource.MANUAL;

    /**
     * The approved leave request that produced this row, when the leave module
     * wrote it. Null for an ordinary attendance row.
     *
     * <p>Kept so cancelling an approved leave can find and remove exactly the
     * days it created, rather than deleting every LEAVE row in the range and
     * taking somebody else's with it.
     */
    @Column(name = "PID_LEAVE_REQUEST")
    private Long pidLeaveRequest;

    @Column(name = "REMARKS", length = 500)
    private String remarks;

    /* ── [V79/V80] Correction ──────────────────────────────────────────────
     *
     * A day already recorded is disputed rather than overwritten. The row keeps
     * saying what it said; the proposal sits beside it until somebody with the
     * Approve right decides. That separation is the whole difference between a
     * correction workflow and an edit — and it is what lets a device punch be
     * challenged without the evidence being quietly rewritten.
     */

    /** REQUESTED, APPROVED or REJECTED. Null when the day is undisputed. */
    @Column(name = "CORRECTION_STATUS", length = 20)
    private String correctionStatus;

    @Column(name = "PROPOSED_STATUS", length = 20)       private String proposedStatus;
    @Column(name = "PROPOSED_CHECK_IN")                  private LocalDateTime proposedCheckIn;
    @Column(name = "PROPOSED_CHECK_OUT")                 private LocalDateTime proposedCheckOut;
    @Column(name = "PROPOSED_OVERTIME_MINUTES")          private Integer proposedOvertimeMinutes;

    @Column(name = "CORRECTION_REQUESTED_BY", length = 50) private String correctionRequestedBy;
    @Column(name = "CORRECTION_REQUESTED_AT")              private LocalDateTime correctionRequestedAt;

    /** Who decided, and when. Set on approval or rejection, not on the request. */
    @Column(name = "CORRECTED_BY", length = 50) private String correctedBy;
    @Column(name = "CORRECTED_AT")              private LocalDateTime correctedAt;

    @Column(name = "CORRECTION_REASON", length = 500) private String correctionReason;

    /* ── [V79] The shift this day was judged against ───────────────────── */

    @Column(name = "PID_SHIFT")     private Long pidShift;
    @Column(name = "SCHEDULED_IN")  private LocalDateTime scheduledIn;
    @Column(name = "SCHEDULED_OUT") private LocalDateTime scheduledOut;

    public static final String CORRECTION_REQUESTED = "REQUESTED";
    public static final String CORRECTION_APPROVED  = "APPROVED";
    public static final String CORRECTION_REJECTED  = "REJECTED";

    /** True while a correction is waiting for somebody to decide. */
    public boolean hasPendingCorrection() {
        return CORRECTION_REQUESTED.equals(correctionStatus);
    }

    /** Clears the proposal once it has been decided, either way. */
    public void clearProposal() {
        proposedStatus = null;
        proposedCheckIn = null;
        proposedCheckOut = null;
        proposedOvertimeMinutes = null;
    }

    /** Minutes to hours, to two places. The only place this conversion happens. */
    public static BigDecimal hours(Integer minutes) {
        if (minutes == null || minutes == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /** Whether payroll counts this day as worked. */
    public boolean isWorkedDay() {
        return HrConstants.AttendanceStatus.WORKED.contains(attendanceStatus);
    }

    public boolean isEditable() {
        return HrConstants.AttendanceSource.isEditable(source);
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
