package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Stores company-wise and product-wise discount percentages for
 * Box / Strip / PCS / Special-customer tiers.
 * These percentages are applied during sales to reduce unit price.
 */
@Entity
@Table(name = "POS_SALES_DISCOUNT",
        uniqueConstraints = @UniqueConstraint(
                name = "UQ_SALES_DISC",
                columnNames = {"PID_ORG", "PID_COMPANY", "PID_PRODUCT", "DISCOUNT_TIER"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosSalesDiscount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SALES_DISC")
    @SequenceGenerator(name = "SQ_SALES_DISC", sequenceName = "SQ_SALES_DISC", allocationSize = 1)
    @Column(name = "PID_DISC")
    private Long pidDisc;

    /** Organisation scope */
    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    /** The pharmacy this row belongs to. Added with V30; every row has one. */
    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    /**
     * NULL means the discount applies to ALL companies under this org.
     * When set, it is company-specific.
     */
    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    /**
     * NULL means the discount applies to ALL products under the company/org.
     * When set, it is product-specific (overrides company-level discount).
     */
    @Column(name = "PID_PRODUCT")
    private Long pidProduct;

    /**
     * Discount tier:
     *   BOX      – discount when selling by box
     *   STRIP    – discount when selling by strip
     *   PCS      – discount when selling by piece
     *   SPECIAL  – special customer discount (applied on top of tier discount)
     */
    @Column(name = "DISCOUNT_TIER", nullable = false, length = 20)
    private String discountTier;

    /** Percentage value, e.g. 5.00 means 5 % */
    @Column(name = "DISCOUNT_PCT", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPct;

    /** 1 = active, 0 = inactive */
    @Column(name = "IS_ACTIVE", nullable = false)
    @Builder.Default
    private Integer isActive = 1;

    // ── transient helpers ──────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_COMPANY", insertable = false, updatable = false)
    private PosCompanyInfo company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_PRODUCT", insertable = false, updatable = false)
    private PosProductInfo product;
}
