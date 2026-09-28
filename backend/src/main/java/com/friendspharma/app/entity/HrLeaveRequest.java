package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A request for time off, and its decision.
 *
 * <p>DRAFT to SUBMITTED to APPROVED or REJECTED, and CANCELLED from any of the
 * first three. An approved request is never edited: {@link #decidedBy} and
 * {@link #decidedAt} are the paper trail, and rewriting the request underneath
 * them would leave the trail describing something that no longer exists.
 * Changing an approved absence means cancelling it and filing again.
 *
 * <p>{@link #days} is stored rather than derived from the two dates, because a
 * half day is half a day and a request spanning a weekend does not count the
 * weekend. What the service computed at the time is what payroll must read back
 * — recomputing it later against a calendar somebody has since edited would
 * change a month that is closed.
 */
@Entity @Table(name = "HR_LEAVE_REQUEST", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrLeaveRequest extends BaseEntity {

    @Id @Column(name = "PID_LEAVE_REQUEST")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_leave_request")
    @SequenceGenerator(name = "seq_hr_leave_request", sequenceName = "SEQ_HR_LEAVE_REQUEST",
                       allocationSize = 1)
    private Long pidLeaveRequest;

    @Column(name = "PID_ORG", nullable = false)     private Long pidOrg;
    @Column(name = "PID_COMPANY")                   private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)  private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;
    @Column(name = "PID_LEAVE_TYPE", nullable = false) private Long pidLeaveType;

    @Column(name = "REQUEST_NO", length = 30)
    private String requestNo;

    @Column(name = "FROM_DATE", nullable = false) private LocalDate fromDate;
    @Column(name = "TO_DATE",   nullable = false) private LocalDate toDate;

    @Column(name = "DAYS", precision = 5, scale = 2, nullable = false)
    private BigDecimal days;

    @Column(name = "IS_HALF_DAY")
    @Builder.Default
    private Integer isHalfDay = 0;

    @Column(name = "REASON", length = 1000)
    private String reason;

    /** See {@link HrConstants.LeaveStatus}. */
    @Column(name = "REQUEST_STATUS", length = 20, nullable = false)
    @Builder.Default
    private String requestStatus = HrConstants.LeaveStatus.DRAFT;

    @Column(name = "SUBMITTED_BY", length = 50) private String submittedBy;
    @Column(name = "SUBMITTED_AT")              private LocalDateTime submittedAt;
    @Column(name = "DECIDED_BY", length = 50)   private String decidedBy;
    @Column(name = "DECIDED_AT")                private LocalDateTime decidedAt;

    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;
    @Column(name = "CANCELLED_REASON", length = 500)  private String cancelledReason;

    public boolean isDraft()     { return HrConstants.LeaveStatus.DRAFT.equals(requestStatus); }
    public boolean isSubmitted() { return HrConstants.LeaveStatus.SUBMITTED.equals(requestStatus); }
    public boolean isApproved()  { return HrConstants.LeaveStatus.APPROVED.equals(requestStatus); }

    /** Only an undecided request may be edited or withdrawn by its author. */
    public boolean isEditable() {
        return isDraft() || isSubmitted();
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
