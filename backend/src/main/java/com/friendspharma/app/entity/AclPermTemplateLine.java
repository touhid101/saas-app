package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One module inside a template, with the rights it grants. */
@Entity
@Table(name = "ACL_PERM_TEMPLATE_LINE")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AclPermTemplateLine {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TEMPLATE_LINE")
    @SequenceGenerator(name = "SQ_TEMPLATE_LINE", sequenceName = "PHARMA.SQ_TEMPLATE_LINE", allocationSize = 1)
    @Column(name = "PID_TEMPLATE_LINE")
    private Long pidTemplateLine;

    @Column(name = "PID_TEMPLATE", nullable = false)
    private Long pidTemplate;

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