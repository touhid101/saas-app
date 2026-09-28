package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A claim for overtime worked on a day.
 *
 * <p><b>This does not feed payroll.</b> {@code PayrollCalculator} already values
 * overtime by reading {@code HR_ATTENDANCE.OVERTIME_MINUTES}; a claim that also
 * fed payroll would pay every approved hour twice. So approving a claim writes
 * the minutes onto the attendance row, and payroll goes on reading exactly what
 * it read before — one source of truth with a workflow in front of it.
 *
 * <p>The same shape as leave, which likewise does not feed payroll: it writes
 * LEAVE attendance rows and lets payroll count days from the register.
 *
 * <p>{@link #pidAttendance} records which row the minutes went to, so
 * cancelling an approved claim takes back exactly what it added rather than
 * zeroing a day that had overtime for another reason.
 */
@Entity @Table(name = "HR_OVERTIME_REQUEST", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrOvertimeRequest extends BaseEntity {

    public static final String DRAFT     = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    /** Closed by ck_hr_ot_status (V81). */
    public static final List<String> ALL_STATUSES =
            List.of(DRAFT, SUBMITTED, APPROVED, REJECTED, CANCELLED);

    @Id @Column(name = "PID_OT_REQUEST")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_ot")
    @SequenceGenerator(name = "seq_hr_ot", sequenceName = "SEQ_HR_OVERTIME_REQUEST",
                       allocationSize = 1)
    private Long pidOtRequest;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    @Column(name = "REQUEST_NO", length = 30)     private String requestNo;
    @Column(name = "OT_DATE", nullable = false)   private LocalDate otDate;

    @Column(name = "CLAIMED_MINUTES", nullable = false) private Integer claimedMinutes;

    /**
     * What was granted, which may be less than was claimed.
     *
     * <p>A separate column from the claim, because an approver who cuts the
     * hours has made a decision worth being able to see afterwards — the same
     * reason an advance keeps its requested and approved amounts apart.
     */
    @Column(name = "APPROVED_MINUTES") private Integer approvedMinutes;

    @Column(name = "REASON", length = 1000) private String reason;

    @Column(name = "REQUEST_STATUS", length = 20, nullable = false)
    @Builder.Default private String requestStatus = DRAFT;

    @Column(name = "SUBMITTED_BY", length = 50) private String submittedBy;
    @Column(name = "SUBMITTED_AT")              private LocalDateTime submittedAt;
    @Column(name = "DECIDED_BY", length = 50)   private String decidedBy;
    @Column(name = "DECIDED_AT")                private LocalDateTime decidedAt;
    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;

    /** The attendance row the approved minutes were written to. */
    @Column(name = "PID_ATTENDANCE") private Long pidAttendance;
    @Column(name = "APPLIED_AT")     private LocalDateTime appliedAt;

    public boolean isDraft()     { return DRAFT.equals(requestStatus); }
    public boolean isSubmitted() { return SUBMITTED.equals(requestStatus); }
    public boolean isApproved()  { return APPROVED.equals(requestStatus); }
    public boolean isActive()    { return "Y".equals(getStatus()); }

    /** Minutes that actually reached the register. Zero until approved. */
    @Transient
    public int effectiveMinutes() {
        return isApproved() && approvedMinutes != null ? approvedMinutes : 0;
    }
}
