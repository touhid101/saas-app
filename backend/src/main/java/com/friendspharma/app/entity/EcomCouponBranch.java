package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

/** Which pharmacies honour a coupon — {@code PHARMA.ECOM_COUPON_BRANCH} (V110). */
@Entity
@Table(name = "ECOM_COUPON_BRANCH", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCouponBranch extends com.friendspharma.app.entity.base.BaseEntity {

    @Id
    @Column(name = "PID_COUPON_BRANCH")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_coupon_branch")
    @SequenceGenerator(name = "seq_ecom_coupon_branch", sequenceName = "SEQ_ECOM_COUPON_BRANCH", allocationSize = 1)
    private Long pidCouponBranch;

    @Column(name = "PID_COUPON", nullable = false) private Long pidCoupon;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;
}
