package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One order a coupon was spent on — {@code PHARMA.ECOM_COUPON_USAGE} (V110).
 *
 * <p>The limits are counted from these rows rather than from a counter on the
 * coupon: two customers redeeming the last use at the same instant would both
 * pass a counter read, and cannot both insert here.
 */
@Entity
@Table(name = "ECOM_COUPON_USAGE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCouponUsage extends com.friendspharma.app.entity.base.BaseEntity {

    @Id
    @Column(name = "PID_USAGE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_coupon_usage")
    @SequenceGenerator(name = "seq_ecom_coupon_usage", sequenceName = "SEQ_ECOM_COUPON_USAGE", allocationSize = 1)
    private Long pidUsage;

    @Column(name = "PID_COUPON", nullable = false)        private Long pidCoupon;
    @Column(name = "PID_ECOM_CUSTOMER", nullable = false) private Long pidEcomCustomer;
    @Column(name = "PID_ECOM_ORDER", nullable = false)    private Long pidEcomOrder;
    @Column(name = "PID_BRANCH", nullable = false)        private Long pidBranch;

    @Column(name = "AMOUNT", precision = 14, scale = 2, nullable = false) private BigDecimal amount;

    @Column(name = "USED_AT", nullable = false)
    @Builder.Default private LocalDateTime usedAt = LocalDateTime.now();

    /** Set when the order was cancelled: the use goes back without losing the record. */
    @Column(name = "RELEASED_AT") private LocalDateTime releasedAt;
}
