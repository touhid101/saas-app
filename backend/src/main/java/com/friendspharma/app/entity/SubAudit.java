package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Who changed a subscription, when, and from what to what.
 *
 * <p>Not a BaseEntity: this table is append-only and has no status to carry or
 * updated_by to fill. A row here is a fact that happened, not a record that can
 * later be edited.
 */
@Entity
@Table(name = "SUB_AUDIT")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SUB_AUDIT")
    @SequenceGenerator(name = "SQ_SUB_AUDIT", sequenceName = "PHARMA.SQ_SUB_AUDIT", allocationSize = 1)
    @Column(name = "PID_AUDIT")
    private Long pidAudit;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    /** ACTIVATED, RENEWED, SUSPENDED, EXPIRED, PAYMENT, PLAN_CHANGED. */
    @Column(name = "EVENT_TYPE", nullable = false, length = 40)
    private String eventType;

    @Column(name = "DETAIL", length = 500)
    private String detail;

    @Column(name = "OLD_VALUE", length = 100)
    private String oldValue;

    @Column(name = "NEW_VALUE", length = 100)
    private String newValue;

    @Column(name = "ACTED_BY", length = 50)
    private String actedBy;

    @Column(name = "ACTED_AT", nullable = false)
    private LocalDateTime actedAt;
}