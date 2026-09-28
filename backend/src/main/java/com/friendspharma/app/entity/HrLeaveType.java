package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A kind of leave a branch grants.
 *
 * <p>{@link #annualQuota} is deliberately nullable and is seeded as null by
 * V73. How many days of each kind a pharmacy grants is that pharmacy's policy
 * and in some cases its statutory obligation; a number invented here would be
 * read as settled, and the first anyone would learn of it is a balance coming
 * out wrong. The Leave screen refuses to open a balance for a type whose quota
 * has not been set, which is a question rather than a guess.
 */
@Entity @Table(name = "HR_LEAVE_TYPE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrLeaveType extends BaseEntity {

    @Id @Column(name = "PID_LEAVE_TYPE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_leave_type")
    @SequenceGenerator(name = "seq_hr_leave_type", sequenceName = "SEQ_HR_LEAVE_TYPE",
                       allocationSize = 1)
    private Long pidLeaveType;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "TYPE_CODE", length = 20, nullable = false)
    private String typeCode;

    @Column(name = "TYPE_NAME", length = 100, nullable = false)
    private String typeName;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;

    @Column(name = "ANNUAL_QUOTA", precision = 6, scale = 2)
    private BigDecimal annualQuota;

    /**
     * Whether days taken are paid.
     *
     * <p>Unpaid leave is the one classification payroll acts on: those days are
     * valued and deducted. Everything else is an absence the employer has
     * already agreed to pay for.
     */
    @Column(name = "IS_PAID")
    @Builder.Default
    private Integer isPaid = 1;

    @Column(name = "CARRY_FORWARD")
    @Builder.Default
    private Integer carryForward = 0;

    @Column(name = "MAX_CARRY_FORWARD", precision = 6, scale = 2)
    private BigDecimal maxCarryForward;

    @Column(name = "REQUIRES_APPROVAL")
    @Builder.Default
    private Integer requiresApproval = 1;

    public boolean isPaidLeave()   { return isPaid != null && isPaid == 1; }
    public boolean isQuotaSet()    { return annualQuota != null; }
    public boolean isActive()      { return "Y".equals(getStatus()); }
}
