package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What a pharmacy can buy.
 *
 * <p>Price is for one billing term rather than per month. A yearly plan carrying
 * a monthly price invites the two to disagree the first time somebody edits one.
 */
@Entity
@Table(name = "SUB_PLAN")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubPlan extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SUB_PLAN")
    @SequenceGenerator(name = "SQ_SUB_PLAN", sequenceName = "PHARMA.SQ_SUB_PLAN", allocationSize = 1)
    @Column(name = "PID_PLAN")
    private Long pidPlan;

    /** BASIC, STANDARD, ENTERPRISE. */
    @Column(name = "PLAN_CODE", nullable = false, length = 30)
    private String planCode;

    @Column(name = "PLAN_NAME", nullable = false, length = 100)
    private String planName;

    @Column(name = "DESCRIPTION", length = 300)
    private String description;

    /** For one billing term, not per month. */
    @Column(name = "PRICE", nullable = false)
    private BigDecimal price;

    /** MONTHLY or YEARLY. */
    @Column(name = "BILLING_TERM", nullable = false, length = 10)
    private String billingTerm;

    /** Null means no cap. */
    @Column(name = "MAX_USERS")
    private Integer maxUsers;

    /** Days past expiry before access stops. */
    @Column(name = "GRACE_DAYS", nullable = false)
    private Integer graceDays;

    /** Whether it can still be sold. */
    @Column(name = "IS_ACTIVE", nullable = false)
    private Integer isActive;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

}