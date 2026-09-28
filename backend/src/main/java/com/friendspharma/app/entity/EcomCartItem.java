package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A line in a cart — {@code PHARMA.ECOM_CART_ITEM} (V104).
 *
 * <p>Product, unit and quantity, and nothing else. No price: the pharmacy's
 * price for this customer's tier is worked out on every read, so a cart cannot
 * quietly carry yesterday's number. No stock either — it moves.
 */
@Entity
@Table(name = "ECOM_CART_ITEM", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCartItem extends BaseEntity {

    @Id
    @Column(name = "PID_CART_ITEM")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_cart_item")
    @SequenceGenerator(name = "seq_ecom_cart_item", sequenceName = "SEQ_ECOM_CART_ITEM", allocationSize = 1)
    private Long pidCartItem;

    @Column(name = "PID_CART", nullable = false)
    private Long pidCart;

    @Column(name = "PID_PRODUCT", nullable = false)
    private Long pidProduct;

    @Column(name = "SALES_UNIT", length = 10, nullable = false)
    private String salesUnit;

    /** In {@link #salesUnit} — boxes, strips or pieces, never converted to pieces here. */
    @Column(name = "QTY", precision = 12, scale = 2, nullable = false)
    private BigDecimal qty;
}
