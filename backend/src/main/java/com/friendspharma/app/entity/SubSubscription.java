package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What one pharmacy is on, and until when.
 *
 * <p>A branch has at most one live subscription — a partial unique index enforces
 * it. Two would make "is this pharmacy paid up" a question with two answers, and
 * the filter would have to pick one.
 */
@Entity
@Table(name = "SUB_SUBSCRIPTION")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubSubscription extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SUB_SUBSCRIPTION")
    @SequenceGenerator(name = "SQ_SUB_SUBSCRIPTION", sequenceName = "PHARMA.SQ_SUB_SUBSCRIPTION", allocationSize = 1)
    @Column(name = "PID_SUBSCRIPTION")
    private Long pidSubscription;

    /** The pharmacy. This is the tenant. */
    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_PLAN", nullable = false)
    private Long pidPlan;

    @Column(name = "STARTS_ON", nullable = false)
    private LocalDate startsOn;

    @Column(name = "EXPIRES_ON", nullable = false)
    private LocalDate expiresOn;

    /** Copied from the plan when written, never read through it: editing a plan must not move the date somebody already trading gets locked out. */
    @Column(name = "GRACE_DAYS", nullable = false)
    private Integer graceDays;

    /** ACTIVE, EXPIRED, SUSPENDED or CANCELLED. */
    @Column(name = "SUB_STATUS", nullable = false, length = 20)
    private String subStatus;

    @Column(name = "CANCELLED_REASON", length = 300)
    private String cancelledReason;

    /** The last day access is allowed: expiry plus the grace agreed at the time. */
    @Transient
    public LocalDate lastDayOfAccess() {
        return expiresOn.plusDays(graceDays == null ? 0 : graceDays);
    }

}