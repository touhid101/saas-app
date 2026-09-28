package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** A ready-made set of module grants, so activating a pharmacy is one choice. */
@Entity
@Table(name = "ACL_PERM_TEMPLATE")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AclPermTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_PERM_TEMPLATE")
    @SequenceGenerator(name = "SQ_PERM_TEMPLATE", sequenceName = "PHARMA.SQ_PERM_TEMPLATE", allocationSize = 1)
    @Column(name = "PID_TEMPLATE")
    private Long pidTemplate;

    @Column(name = "TEMPLATE_CODE", nullable = false, length = 30)
    private String templateCode;

    @Column(name = "TEMPLATE_NAME", nullable = false, length = 100)
    private String templateName;

    @Column(name = "DESCRIPTION", length = 300)
    private String description;

    /** The plan this suits. Advisory — any template may be used with any plan. */
    @Column(name = "PLAN_CODE", length = 30)
    private String planCode;

    @Column(name = "IS_ACTIVE", nullable = false)
    private Integer isActive;

    @Column(name = "SORT_ORDER", nullable = false)
    private Integer sortOrder;

}