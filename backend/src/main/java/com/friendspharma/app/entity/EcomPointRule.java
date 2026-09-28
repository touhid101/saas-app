package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What a loyalty point is worth, and how one is earned —
 * {@code PHARMA.ECOM_POINT_RULE} (V111).
 *
 * <p>Every number here is somebody's decision, so there is no default rule.
 * Until one exists nothing earns and nothing redeems: a scheme that switched
 * itself on with an invented exchange rate would be giving away money nobody
 * agreed to.
 */
@Entity
@Table(name = "ECOM_POINT_RULE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomPointRule extends com.friendspharma.app.entity.base.BaseEntity {

    public static final String ALL_BRANCHES = "ALL_BRANCHES", SELECTED_BRANCHES = "SELECTED_BRANCHES";
    public static final String TIER_ALL = "ALL";

    @Id
    @Column(name = "PID_POINT_RULE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_point_rule")
    @SequenceGenerator(name = "seq_ecom_point_rule", sequenceName = "SEQ_ECOM_POINT_RULE", allocationSize = 1)
    private Long pidPointRule;

    @Column(name = "RULE_NAME", length = 150, nullable = false) private String ruleName;
    @Column(name = "DESCRIPTION", length = 300)                 private String description;

    /** Points given for every 100 spent — how a pharmacy says it out loud. */
    @Column(name = "EARN_POINTS_PER_100", precision = 10, scale = 2, nullable = false)
    private BigDecimal earnPointsPer100;
    @Column(name = "MIN_ORDER_TO_EARN", precision = 14, scale = 2) private BigDecimal minOrderToEarn;

    /** What one point takes off a bill. The exchange rate, with no default. */
    @Column(name = "POINT_VALUE", precision = 10, scale = 4, nullable = false) private BigDecimal pointValue;
    @Column(name = "MIN_POINTS_TO_REDEEM") private Integer minPointsToRedeem;
    @Column(name = "MAX_REDEEM_PERCENT", precision = 5, scale = 2) private BigDecimal maxRedeemPercent;

    /** Points unused for this many months expire. Null means they do not. */
    @Column(name = "EXPIRY_MONTHS") private Integer expiryMonths;

    @Column(name = "BRANCH_SCOPE", length = 20, nullable = false) private String branchScope;
    @Column(name = "CUSTOMER_TYPE_SCOPE", length = 20, nullable = false)
    @Builder.Default private String customerTypeScope = TIER_ALL;

    @Column(name = "STARTS_AT") private LocalDateTime startsAt;
    @Column(name = "ENDS_AT")   private LocalDateTime endsAt;

    @Column(name = "IS_ACTIVE", length = 1, nullable = false)
    @Builder.Default private String isActive = "Y";

    @Column(name = "PID_OWNER_BRANCH") private Long pidOwnerBranch;
    @Column(name = "PID_ORG")          private Long pidOrg;

    public boolean isLive() { return "Y".equals(isActive); }
}
