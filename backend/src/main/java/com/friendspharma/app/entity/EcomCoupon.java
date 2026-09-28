package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A code a customer types — {@code PHARMA.ECOM_COUPON} (V110).
 *
 * <p>The one promotion the ERP never had. The price list, the standing trade
 * discount and the manual invoice discount all exist already; none of them is
 * something a customer can be given and choose to spend.
 */
@Entity
@Table(name = "ECOM_COUPON", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCoupon extends com.friendspharma.app.entity.base.BaseEntity {

    public static final String PERCENT = "PERCENT", FIXED = "FIXED", FREE_DELIVERY = "FREE_DELIVERY";
    public static final String ALL_BRANCHES = "ALL_BRANCHES", SELECTED_BRANCHES = "SELECTED_BRANCHES";
    public static final String TIER_ALL = "ALL";

    @Id
    @Column(name = "PID_COUPON")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_coupon")
    @SequenceGenerator(name = "seq_ecom_coupon", sequenceName = "SEQ_ECOM_COUPON", allocationSize = 1)
    private Long pidCoupon;

    @Column(name = "CODE", length = 40, nullable = false)        private String code;
    @Column(name = "COUPON_NAME", length = 150, nullable = false) private String couponName;
    @Column(name = "DESCRIPTION", length = 300)                  private String description;

    @Column(name = "DISCOUNT_TYPE", length = 20, nullable = false) private String discountType;
    @Column(name = "DISCOUNT_VALUE", precision = 12, scale = 2)    private BigDecimal discountValue;
    @Column(name = "MAX_DISCOUNT_AMOUNT", precision = 12, scale = 2) private BigDecimal maxDiscountAmount;
    @Column(name = "MIN_ORDER_AMOUNT", precision = 14, scale = 2)  private BigDecimal minOrderAmount;

    @Column(name = "BRANCH_SCOPE", length = 20, nullable = false)  private String branchScope;
    @Column(name = "CUSTOMER_TYPE_SCOPE", length = 20, nullable = false)
    @Builder.Default private String customerTypeScope = TIER_ALL;
    @Column(name = "FIRST_ORDER_ONLY", length = 1, nullable = false)
    @Builder.Default private String firstOrderOnly = "N";

    @Column(name = "USAGE_LIMIT_TOTAL")        private Integer usageLimitTotal;
    @Column(name = "USAGE_LIMIT_PER_CUSTOMER") private Integer usageLimitPerCustomer;

    @Column(name = "STARTS_AT") private LocalDateTime startsAt;
    @Column(name = "ENDS_AT")   private LocalDateTime endsAt;

    @Column(name = "IS_ACTIVE", length = 1, nullable = false)
    @Builder.Default private String isActive = "Y";

    @Column(name = "PID_OWNER_BRANCH") private Long pidOwnerBranch;
    @Column(name = "PID_ORG")          private Long pidOrg;

    public boolean isLive() { return "Y".equals(isActive); }
    public boolean isFirstOrderOnly() { return "Y".equals(firstOrderOnly); }
}
