package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One level's decision, kept whatever happened afterwards.
 *
 * <p>Including the approvals that came before a rejection. "Approved by the
 * manager and refused by the administrator" is a different story from
 * "refused", and only a trail can tell them apart.
 */
@Entity @Table(name = "HR_APPROVAL_ACTION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrApprovalAction extends BaseEntity {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id @Column(name = "PID_ACTION")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_approval_action")
    @SequenceGenerator(name = "seq_hr_approval_action", sequenceName = "SEQ_HR_APPROVAL_ACTION",
                       allocationSize = 1)
    private Long pidAction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_APPROVAL", nullable = false)
    private HrApprovalRequest approval;

    @Column(name = "LEVEL_NO", nullable = false)   private Integer levelNo;
    @Column(name = "LEVEL_LABEL", length = 120)    private String levelLabel;
    @Column(name = "APPROVER_ROLE", length = 20)   private String approverRole;

    @Column(name = "DECISION", length = 20, nullable = false) private String decision;
    @Column(name = "DECIDED_BY", length = 50) private String decidedBy;
    @Column(name = "DECIDED_AT")              private LocalDateTime decidedAt;
    @Column(name = "REMARKS", length = 1000)  private String remarks;

    public boolean isActive() { return "Y".equals(getStatus()); }
}
