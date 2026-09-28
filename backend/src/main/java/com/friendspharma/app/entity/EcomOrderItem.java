package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A line of a marketplace order, as ordered — {@code PHARMA.ECOM_ORDER_ITEM} (V105).
 *
 * <p>What the customer asked for and was charged. What the pharmacy actually
 * dispensed, batch by batch, is in {@code POS_TRANSACTION_DTL}: one requested
 * line can become several rows there when it spans lots.
 */
@Entity
@Table(name = "ECOM_ORDER_ITEM", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomOrderItem extends BaseEntity {

    @Id
    @Column(name = "PID_ORDER_ITEM")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_order_item")
    @SequenceGenerator(name = "seq_ecom_order_item", sequenceName = "SEQ_ECOM_ORDER_ITEM", allocationSize = 1)
    private Long pidOrderItem;

    @Column(name = "PID_ECOM_ORDER", nullable = false)
    private Long pidEcomOrder;

    @Column(name = "PID_PRODUCT", nullable = false)
    private Long pidProduct;

    @Column(name = "PID_GLOBAL_PRODUCT")
    private Long pidGlobalProduct;

    @Column(name = "PRODUCT_NAME_SNAPSHOT", length = 200) private String productNameSnapshot;
    @Column(name = "GENERIC_SNAPSHOT", length = 200)      private String genericSnapshot;
    @Column(name = "COMPANY_SNAPSHOT", length = 150)      private String companySnapshot;

    @Column(name = "SALES_UNIT", length = 10, nullable = false) private String salesUnit;
    @Column(name = "QTY", precision = 12, scale = 2, nullable = false) private BigDecimal qty;
    @Column(name = "PCS_PER_UNIT_SNAPSHOT") private Integer pcsPerUnitSnapshot;
    @Column(name = "QTY_PCS", precision = 14, scale = 2) private BigDecimal qtyPcs;
    @Column(name = "MRP_SNAPSHOT", precision = 14, scale = 4) private BigDecimal mrpSnapshot;
    @Column(name = "UNIT_PRICE_SNAPSHOT", precision = 14, scale = 4, nullable = false) private BigDecimal unitPriceSnapshot;
    @Column(name = "DISCOUNT_PCT_SNAPSHOT", precision = 8, scale = 4) private BigDecimal discountPctSnapshot;
    @Column(name = "LINE_TOTAL", precision = 14, scale = 2, nullable = false) private BigDecimal lineTotal;
    @Column(name = "PRESCRIPTION_FLAG_SNAPSHOT", length = 1) private String prescriptionFlagSnapshot;
}
