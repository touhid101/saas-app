package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * What one pharmacy's plan includes.
 *
 * <p>Narrows the role, never widens it. A Basic subscriber must not reach an
 * Enterprise module because somebody ticked a box on the wrong screen, so
 * widening is simply not expressible: the resolver takes the smaller of the two.
 *
 * <p>A branch with no rows here is unrestricted — an empty grant means "as the
 * role allows", not "nothing". That is what let this ship without stopping every
 * existing branch on the day it landed.
 */
@Entity
@Table(name = "ACL_TENANT_MODULE")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AclTenantModule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TENANT_MODULE")
    @SequenceGenerator(name = "SQ_TENANT_MODULE", sequenceName = "PHARMA.SQ_TENANT_MODULE", allocationSize = 1)
    @Column(name = "PID_TENANT_MODULE")
    private Long pidTenantModule;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_MODULE", nullable = false)
    private Long pidModule;

    @Column(name = "CAN_VIEW", nullable = false)
    private Integer canView;

    @Column(name = "CAN_CREATE", nullable = false)
    private Integer canCreate;

    @Column(name = "CAN_EDIT", nullable = false)
    private Integer canEdit;

    @Column(name = "CAN_DELETE", nullable = false)
    private Integer canDelete;

    @Column(name = "CAN_APPROVE", nullable = false)
    private Integer canApprove;

    @Column(name = "CAN_EXPORT", nullable = false)
    private Integer canExport;

    @Column(name = "CAN_PRINT", nullable = false)
    private Integer canPrint;

}