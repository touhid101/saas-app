package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One product line inside a purchase requisition.
 * AI-sourced lines carry an aiRecommendedQty that users can override before approval.
 */
@Entity
@Table(name = "POS_REQUISITION_DTL",
        indexes = @Index(name = "IDX_REQ_DTL_MST", columnList = "PID_REQUISITION"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosRequisitionDtl extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_REQ_DTL")
    @SequenceGenerator(name = "SQ_REQ_DTL", sequenceName = "SQ_REQ_DTL", allocationSize = 1)
    @Column(name = "PID_REQ_DTL")
    private Long pidReqDtl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PID_REQUISITION", nullable = false)
    private PosRequisitionMst requisition;

    @Column(name = "PID_PRODUCT", nullable = false)
    private Long pidProduct;

    @Column(name = "PRODUCT_NAME", length = 300)
    private String productName;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "COMPANY_NAME", length = 200)
    private String companyName;

    /** AI-suggested quantity (editable before approval) */
    @Column(name = "AI_RECOMMENDED_QTY", precision = 10, scale = 2)
    private BigDecimal aiRecommendedQty;

    /** Final requested quantity (user can override aiRecommendedQty) */
    @Column(name = "REQUESTED_QTY", nullable = false, precision = 10, scale = 2)
    private BigDecimal requestedQty;

    /** BOX | STRIP | PCS */
    @Column(name = "UNIT", length = 20)
    @Builder.Default
    private String unit = "BOX";

    /** Estimated unit cost — informational only */
    @Column(name = "ESTIMATED_UNIT_COST", precision = 15, scale = 2)
    private BigDecimal estimatedUnitCost;

    /** HIGH | MEDIUM | LOW — AI priority ranking */
    @Column(name = "PRIORITY", length = 10)
    @Builder.Default
    private String priority = "MEDIUM";

    @Column(name = "REMARKS", length = 300)
    private String remarks;

    /** Current stock level at time of requisition creation */
    @Column(name = "CURRENT_STOCK", precision = 10, scale = 2)
    private BigDecimal currentStock;

    /** Average monthly sales used by AI to derive recommended qty */
    @Column(name = "AVG_MONTHLY_SALES", precision = 10, scale = 2)
    private BigDecimal avgMonthlySales;

    /**
     * The purchase order this line was placed on.
     *
     * <p>Null until the requisition is split. It lives on the line rather than
     * only on the master because a requisition covering four manufacturers
     * becomes four orders, and each line goes to exactly one of them.
     */
    @Column(name = "PID_PURCHASE_ORDER")
    private Long pidPurchaseOrder;
}
