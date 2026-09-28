package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Grants a role (AclGroup) access to a module with fine-grained CRUD flags.
 * One row = one role × one module combination.
 */
@Entity
@Table(name = "POS_PERMISSIONS",
        uniqueConstraints = @UniqueConstraint(
                name = "UQ_PERM_ROLE_MODULE",
                columnNames = {"PID_ROLE", "PID_MODULE"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosPermissions extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_POS_PERM")
    @SequenceGenerator(name = "SQ_POS_PERM", sequenceName = "SQ_POS_PERM", allocationSize = 1)
    @Column(name = "PID_PERMISSION")
    private Long pidPermission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PID_ROLE", nullable = false)
    private AclGroup role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PID_MODULE", nullable = false)
    private AclModuleInfo module;

    @Column(name = "CAN_VIEW",   nullable = false) @Builder.Default private Integer canView   = 0;
    @Column(name = "CAN_CREATE", nullable = false) @Builder.Default private Integer canCreate = 0;
    @Column(name = "CAN_EDIT",   nullable = false) @Builder.Default private Integer canEdit   = 0;
    @Column(name = "CAN_DELETE", nullable = false) @Builder.Default private Integer canDelete = 0;
    @Column(name = "CAN_EXPORT", nullable = false) @Builder.Default private Integer canExport = 0;
    @Column(name = "CAN_APPROVE",nullable = false) @Builder.Default private Integer canApprove= 0;
    /** The seventh right. Anyone who could already export was given it in V33. */
    @Column(name = "CAN_PRINT",  nullable = false) @Builder.Default private Integer canPrint  = 0;
}
