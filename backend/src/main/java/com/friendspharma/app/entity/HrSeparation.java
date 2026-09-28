package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Somebody leaving.
 *
 * <p><b>The employee row is never deleted.</b> Staff records are referenced by
 * invoices and by the delivery history; removing one would leave those rows
 * pointing at nothing, and a report of who sold what last year would quietly
 * lose a person. Separation is therefore a record beside the employee, and what
 * it changes on the employee is {@code STATUS} — the same flag every other
 * master in this schema uses to mean "not in the pickers any more".
 *
 * <p><b>No final settlement is computed.</b> {@link #settlementAmount} is
 * entered by whoever settles it. What belongs in a final settlement — notice
 * pay, unused leave encashment, gratuity — is policy and in places statute,
 * and this implementation does not answer it. Recorded in
 * {@code 02-hr-module-srs.md} as BUSINESS POLICY CONFIRMATION REQUIRED.
 */
@Entity @Table(name = "HR_SEPARATION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrSeparation extends BaseEntity {

    @Id @Column(name = "PID_SEPARATION")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_separation")
    @SequenceGenerator(name = "seq_hr_separation", sequenceName = "SEQ_HR_SEPARATION",
                       allocationSize = 1)
    private Long pidSeparation;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    /** See {@link HrConstants.SeparationType}. */
    @Column(name = "SEPARATION_TYPE", length = 20, nullable = false)
    private String separationType;

    @Column(name = "NOTICE_DATE")                        private LocalDate noticeDate;
    @Column(name = "LAST_WORKING_DATE", nullable = false) private LocalDate lastWorkingDate;
    @Column(name = "NOTICE_PERIOD_DAYS")                 private Integer noticePeriodDays;
    @Column(name = "REASON", length = 1000)              private String reason;

    /** See {@link HrConstants.SeparationStatus}. */
    @Column(name = "SEPARATION_STATUS", length = 20, nullable = false)
    @Builder.Default private String separationStatus = HrConstants.SeparationStatus.DRAFT;

    @Column(name = "DECIDED_BY", length = 50) private String decidedBy;
    @Column(name = "DECIDED_AT")              private LocalDateTime decidedAt;
    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;

    /** See {@link HrConstants.SettlementStatus}. */
    @Column(name = "SETTLEMENT_STATUS", length = 20, nullable = false)
    @Builder.Default private String settlementStatus = HrConstants.SettlementStatus.PENDING;

    @Column(name = "SETTLEMENT_AMOUNT", precision = 18, scale = 2) private BigDecimal settlementAmount;
    @Column(name = "SETTLEMENT_DATE")                              private LocalDate settlementDate;
    @Column(name = "REMARKS", length = 1000)                       private String remarks;

    public boolean isApproved() {
        return HrConstants.SeparationStatus.APPROVED.equals(separationStatus);
    }

    public boolean isEditable() {
        return HrConstants.SeparationStatus.DRAFT.equals(separationStatus)
                || HrConstants.SeparationStatus.SUBMITTED.equals(separationStatus);
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
