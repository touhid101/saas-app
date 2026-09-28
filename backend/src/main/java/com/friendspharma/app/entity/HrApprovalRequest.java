package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One object travelling through a chain of signatures.
 *
 * <h3>It sits in front of the existing workflow, not in place of it</h3>
 *
 * <p>The leave request, the advance, the claim all keep their own status and
 * their own {@code DECIDED_BY}. This row only answers "whose signature is next",
 * and the object stays SUBMITTED until the last one is given. Every service kept
 * every line of its approve(); it asks first whether this decision is the final
 * one and returns early when it is not.
 *
 * <h3>Why the target is a code and an id, not a foreign key</h3>
 *
 * <p>{@link #workflowCode} and {@link #refId} point at a different table each
 * time. The service that started the chain is the one that reads it back, and
 * it knows which table it meant — a polymorphic foreign key would be a
 * constraint the database could not check anyway.
 *
 * <h3>The chain is frozen at the start</h3>
 *
 * <p>{@link #totalLevels}, {@link #flowName} and {@link #routedValue} are copied
 * when the chain begins. Editing a flow does not change a request already
 * travelling through it, for the same reason an approved increment is not
 * recomputed: a decision half-made under one rule should not finish under
 * another.
 */
@Entity @Table(name = "HR_APPROVAL_REQUEST", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrApprovalRequest extends BaseEntity {

    public static final String PENDING   = "PENDING";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    @Id @Column(name = "PID_APPROVAL")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_approval_request")
    @SequenceGenerator(name = "seq_hr_approval_request", sequenceName = "SEQ_HR_APPROVAL_REQUEST",
                       allocationSize = 1)
    private Long pidApproval;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;

    @Column(name = "WORKFLOW_CODE", length = 20, nullable = false) private String workflowCode;
    @Column(name = "REF_ID", nullable = false)                   private Long refId;
    @Column(name = "PID_EMPLOYEE", nullable = false)             private Long pidEmployee;

    @Column(name = "PID_FLOW")                    private Long pidFlow;
    @Column(name = "FLOW_NAME", length = 120)     private String flowName;
    @Column(name = "ROUTED_VALUE", precision = 18, scale = 2) private BigDecimal routedValue;

    @Column(name = "CURRENT_LEVEL", nullable = false)
    @Builder.Default private Integer currentLevel = 1;

    @Column(name = "TOTAL_LEVELS", nullable = false) private Integer totalLevels;

    @Column(name = "REQUEST_STATUS", length = 20, nullable = false)
    @Builder.Default private String requestStatus = PENDING;

    @Column(name = "STARTED_BY", length = 50) private String startedBy;
    @Column(name = "STARTED_AT")              private LocalDateTime startedAt;
    @Column(name = "CLOSED_BY", length = 50)  private String closedBy;
    @Column(name = "CLOSED_AT")               private LocalDateTime closedAt;

    @OneToMany(mappedBy = "approval", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("levelNo ASC, pidAction ASC")
    @Builder.Default
    private List<HrApprovalAction> actions = new ArrayList<>();

    public boolean isPending() { return PENDING.equals(requestStatus); }
    public boolean isActive()  { return "Y".equals(getStatus()); }

    /** True when the level about to sign is the last one. */
    @Transient
    public boolean isFinalLevel() {
        return currentLevel != null && totalLevels != null && currentLevel >= totalLevels;
    }

    /** "Level 2 of 3", for a screen that has to say where a request has got to. */
    @Transient
    public String progress() {
        return "Level " + currentLevel + " of " + totalLevels;
    }
}
