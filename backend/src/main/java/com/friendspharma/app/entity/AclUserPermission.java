package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One person's exception to their role.
 *
 * <p>Every right is nullable, and null means "whatever the role and the plan
 * say". A row obliged to state all seven would turn a small exception into a full
 * copy of the role, and the copy would drift the first time the role changed.
 *
 * <p>An override still cannot exceed what the pharmacy bought.
 */
@Entity
@Table(name = "ACL_USER_PERMISSION")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AclUserPermission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_USER_PERMISSION")
    @SequenceGenerator(name = "SQ_USER_PERMISSION", sequenceName = "PHARMA.SQ_USER_PERMISSION", allocationSize = 1)
    @Column(name = "PID_USER_PERMISSION")
    private Long pidUserPermission;

    @Column(name = "PID_USER", nullable = false)
    private Long pidUser;

    @Column(name = "PID_MODULE", nullable = false)
    private Long pidModule;

    @Column(name = "CAN_VIEW")
    private Integer canView;

    @Column(name = "CAN_CREATE")
    private Integer canCreate;

    @Column(name = "CAN_EDIT")
    private Integer canEdit;

    @Column(name = "CAN_DELETE")
    private Integer canDelete;

    @Column(name = "CAN_APPROVE")
    private Integer canApprove;

    @Column(name = "CAN_EXPORT")
    private Integer canExport;

    @Column(name = "CAN_PRINT")
    private Integer canPrint;

    @Column(name = "NOTE", length = 200)
    private String note;

}