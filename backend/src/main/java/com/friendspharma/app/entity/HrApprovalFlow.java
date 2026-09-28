package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A chain of signatures for one module at one branch.
 *
 * <p>Nothing is seeded. With no flow for a module, that module decides the way
 * it decided before this table existed: one Approve right, one decision. The
 * same compatibility contract the working policy and the PF scheme follow.
 *
 * <p>{@link #minValue} is the floor this flow applies from, measured in whatever
 * the module measures itself in — days for leave, money for an advance or a
 * claim. Only the calling service knows which, so only the calling service
 * supplies the figure. Null means it applies to anything.
 */
@Entity @Table(name = "HR_APPROVAL_FLOW", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrApprovalFlow extends BaseEntity {

    @Id @Column(name = "PID_FLOW")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_approval_flow")
    @SequenceGenerator(name = "seq_hr_approval_flow", sequenceName = "SEQ_HR_APPROVAL_FLOW",
                       allocationSize = 1)
    private Long pidFlow;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;

    @Column(name = "WORKFLOW_CODE", length = 20, nullable = false) private String workflowCode;
    @Column(name = "FLOW_NAME", length = 120, nullable = false)  private String flowName;

    @Column(name = "MIN_VALUE", precision = 18, scale = 2) private BigDecimal minValue;
    @Column(name = "DESCRIPTION", length = 500)            private String description;

    @OneToMany(mappedBy = "flow", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("levelNo ASC")
    @Builder.Default
    private List<HrApprovalFlowLevel> levels = new ArrayList<>();

    public boolean isActive() { return "Y".equals(getStatus()); }

    /** A flow with no levels signs nothing and is treated as absent. */
    @Transient
    public boolean isUsable() { return !levels.isEmpty(); }
}
