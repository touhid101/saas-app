package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single line in a POS cart.
 * Mirrors the structure of POS_TRANSACTION_DTL so finalisation is a straight copy.
 */
@Entity
@Table(name = "POS_CART_ITEM",
        indexes = @Index(name = "IDX_CART_ITEM_CART", columnList = "PID_CART"))
@NoArgsConstructor @AllArgsConstructor @Builder
public class PosCartItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_POS_CART_ITEM")
    @SequenceGenerator(name = "SQ_POS_CART_ITEM", sequenceName = "SQ_POS_CART_ITEM", allocationSize = 1)
    @Column(name = "PID_CART_ITEM")
    private Long pidCartItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PID_CART", nullable = false)
    private PosCart cart;

    @Column(name = "PID_PRODUCT", nullable = false)
    private Long pidProduct;

    @Column(name = "PRODUCT_NAME", length = 300)
    private String productName;

    @Column(name = "PRODUCT_IMAGE_URL", length = 500)
    private String productImageUrl;

    /** BOX | STRIP | PCS */
    @Column(name = "SALES_UNIT", nullable = false, length = 20)
    private String salesUnit;

    @Column(name = "QTY", nullable = false, precision = 10, scale = 2)
    private BigDecimal qty;

    @Column(name = "UNIT_PRICE", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    /** Discount percentage resolved from POS_SALES_DISCOUNT */
    @Column(name = "DISCOUNT_PCT", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal discountPct = BigDecimal.ZERO;

    @Column(name = "LINE_TOTAL", nullable = false, precision = 15, scale = 2)
    private BigDecimal lineTotal;

    /** Batch expiry — carried from FEFO allocation */
    @Column(name = "EXPIRY_DATE")
    private LocalDate expiryDate;

    /** Batch number */
    @Column(name = "BATCH_NO", length = 50)
    private String batchNo;

    // ── Getters and Setters ──────────────────────────────────────
    public Long getPidCartItem() { return pidCartItem; }
    public void setPidCartItem(Long pidCartItem) { this.pidCartItem = pidCartItem; }

    public PosCart getCart() { return cart; }
    public void setCart(PosCart cart) { this.cart = cart; }

    public Long getPidProduct() { return pidProduct; }
    public void setPidProduct(Long pidProduct) { this.pidProduct = pidProduct; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getProductImageUrl() { return productImageUrl; }
    public void setProductImageUrl(String productImageUrl) { this.productImageUrl = productImageUrl; }

    public String getSalesUnit() { return salesUnit; }
    public void setSalesUnit(String salesUnit) { this.salesUnit = salesUnit; }

    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getDiscountPct() { return discountPct; }
    public void setDiscountPct(BigDecimal discountPct) { this.discountPct = discountPct; }

    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
}
