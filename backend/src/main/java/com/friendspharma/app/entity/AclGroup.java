package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Maps to ACL_GROUP — serves as the Role entity in the React RBAC system.
 * Extended: ROLE_COLOR, ROLE_CODE for frontend role badges.
 */
@Entity
@Table(name = "ACL_GROUP", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AclGroup extends BaseEntity {

    @Id
    @Column(name = "PID_GROUP")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acl_group")
    @SequenceGenerator(name = "seq_acl_group", sequenceName = "SEQ_ACL_GROUP", allocationSize = 1)
    private Long pidGroup;

    @Column(name = "PID_ORG")
    private Long pidOrg;

    @Column(name = "PID_MODULE")
    private Long pidModule;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "GROUP_NAME", length = 50, nullable = false)
    private String groupName;

    @Column(name = "GROUP_DESCRIPTION", length = 250)
    private String groupDescription;

    /** [NEW] Hex color for role badge in React UI */
    @Column(name = "ROLE_COLOR", length = 20)
    private String roleColor;

    /** [NEW] Role code identifier (ROLE01..ROLE09) */
    @Column(name = "ROLE_CODE", length = 10)
    private String groupCode;   // stored as ROLE_CODE, exposed as groupCode

    /** [NEW] Active flag */
    @Column(name = "IS_ACTIVE")
    @Builder.Default
    private Integer isActive = 1;

    // plain alias for legacy roleCode name
    public String getRoleCode()          { return groupCode; }
    public String getDescription()       { return groupDescription; }
}
