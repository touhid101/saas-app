package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_BRANCH", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllBranch extends BaseEntity {

    @Id @Column(name = "PID_BRANCH")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_branch")
    @SequenceGenerator(name = "seq_branch", sequenceName = "SEQ_ALL_BRANCH", allocationSize = 1)
    private Long pidBranch;

    @Column(name = "PID_ORG")
    private Long pidOrg;

    /**
     * Where this pharmacy stands: PENDING, ACTIVE, SUSPENDED or EXPIRED.
     *
     * <p>Separate from {@code status}, which is the one-character audit flag every
     * table carries and cannot hold a lifecycle. An administrator suspending a
     * branch and a subscription running out are different decisions, and this
     * column records the first while the subscription records the second.
     */
    @Column(name = "TENANT_STATUS", length = 20)
    private String tenantStatus;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "BRANCH_NAME", length = 100, nullable = false)
    private String branchName;

    @Column(name = "BRANCH_ADDRESS", length = 200)
    private String branchAddress;

    @Column(name = "BRANCH_EMAIL", length = 50)
    private String branchEmail;

    @Column(name = "BRANCH_PHONE_NO", length = 20)
    private String branchPhoneNo;

    /** Public path to this pharmacy's logo, or null. The file lives on disk. */
    @Column(name = "LOGO_URL", length = 300)
    private String logoUrl;

    @Column(name = "REPORT_HEADER", length = 2000)
    private String reportHeader;

    /** [NEW] FK to ALL_REGION */
    @Column(name = "PID_REGION")
    private Long pidRegion;

    /* ── [V103] The online store (docs/ecommerce Phase 2). None of these is read
       by the ERP; the marketplace lists a pharmacy only when ecomEnabled is Y. ── */
    @Column(name = "ECOM_ENABLED", length = 1)
    private String ecomEnabled;

    /* Who this branch may sell to online: RETAIL, WHOLESALE or BOTH (V112).
       Set by hand on the branch form. A warehouse holding stock is not a
       wholesale pharmacy until somebody says it is. */
    @Column(name = "BRANCH_TRADE_TYPE", length = 10)
    @Builder.Default private String branchTradeType = "RETAIL";

    @Column(name = "LATITUDE", precision = 9, scale = 6)
    private java.math.BigDecimal latitude;

    @Column(name = "LONGITUDE", precision = 9, scale = 6)
    private java.math.BigDecimal longitude;

    @Column(name = "ECOM_DELIVERY_RADIUS_KM", precision = 6, scale = 2)
    private java.math.BigDecimal ecomDeliveryRadiusKm;

    @Column(name = "ECOM_MIN_ORDER_AMOUNT", precision = 14, scale = 2)
    private java.math.BigDecimal ecomMinOrderAmount;

    @Column(name = "ECOM_OPENING_HOURS", length = 200)
    private String ecomOpeningHours;

    /* ── [V108] What this pharmacy charges to deliver. No type means free,
       which is what every branch did before the column existed. ── */
    @Column(name = "ECOM_DELIVERY_FEE_TYPE", length = 10)
    private String ecomDeliveryFeeType;

    @Column(name = "ECOM_DELIVERY_FLAT_FEE", precision = 12, scale = 2)
    private java.math.BigDecimal ecomDeliveryFlatFee;

    @Column(name = "ECOM_DELIVERY_FEE_PER_KM", precision = 12, scale = 2)
    private java.math.BigDecimal ecomDeliveryFeePerKm;

    @Column(name = "ECOM_DELIVERY_FREE_ABOVE", precision = 14, scale = 2)
    private java.math.BigDecimal ecomDeliveryFreeAbove;

    @Column(name = "ECOM_DESCRIPTION", length = 500)
    private String ecomDescription;

    public boolean isEcomEnabled() { return "Y".equals(ecomEnabled); }

    /** Sells to retail customers browsing nearby — RETAIL or BOTH. */
    /**
     * Y once staff have seen this pharmacy's drug licence (V114).
     *
     * <p>Default N for every existing branch: back-filling Y would assert a
     * check that never happened. The storefront's Verified badge reads this
     * and nothing else.
     */
    @Column(name = "BRANCH_VERIFIED", length = 1, nullable = false)
    @Builder.Default
    private String branchVerified = "N";

    @Column(name = "DRUG_LICENSE_NO", length = 60)
    private String drugLicenseNo;

    @Column(name = "VERIFIED_BY", length = 100)
    private String verifiedBy;

    @Column(name = "VERIFIED_AT")
    private java.time.LocalDateTime verifiedAt;

    public boolean isVerified() { return "Y".equalsIgnoreCase(branchVerified); }

    public boolean sellsRetail() { return !"WHOLESALE".equals(branchTradeType); }

    /** May be chosen by a wholesaler or a special customer — WHOLESALE or BOTH. */
    public boolean sellsWholesale() { return "WHOLESALE".equals(branchTradeType) || "BOTH".equals(branchTradeType); }
}
