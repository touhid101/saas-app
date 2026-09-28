package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One signature in a chain.
 *
 * <p>{@link #approverRole} is a role code, or {@link #MANAGER} meaning the
 * employee's own manager read from {@code HR_EMPLOYEES.PID_MANAGER}. A role
 * outlives a person; a named approver does not, and a flow that breaks when
 * somebody resigns is a flow nobody trusts.
 *
 * <p>A level <b>narrows</b> who may decide and never widens it: the module's
 * Approve right is still required, so a flow cannot hand somebody an authority
 * the RBAC screen did not give them.
 */
@Entity @Table(name = "HR_APPROVAL_FLOW_LEVEL", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrApprovalFlowLevel extends BaseEntity {

    /** The employee's own manager, rather than a role. */
    public static final String MANAGER = "MANAGER";

    @Id @Column(name = "PID_FLOW_LEVEL")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_approval_flow_level")
    @SequenceGenerator(name = "seq_hr_approval_flow_level",
                       sequenceName = "SEQ_HR_APPROVAL_FLOW_LEVEL", allocationSize = 1)
    private Long pidFlowLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_FLOW", nullable = false)
    private HrApprovalFlow flow;

    @Column(name = "LEVEL_NO", nullable = false) private Integer levelNo;

    @Column(name = "APPROVER_ROLE", length = 20, nullable = false) private String approverRole;
    @Column(name = "LEVEL_LABEL", length = 120)                    private String levelLabel;

    public boolean isActive() { return "Y".equals(getStatus()); }

    @Transient
    public boolean isManagerLevel() { return MANAGER.equals(approverRole); }

    /** What to call this step when nobody named it. */
    @Transient
    public String label() {
        if (levelLabel != null && !levelLabel.isBlank()) return levelLabel;
        return isManagerLevel() ? "The employee's manager" : approverRole;
    }
}
