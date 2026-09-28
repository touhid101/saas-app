package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A customer's cart at one pharmacy — {@code PHARMA.ECOM_CART} (V104).
 *
 * <p>The cart belongs to the pharmacy that will pack it: stock and price are
 * that pharmacy's, so a line cannot be moved between pharmacies, only added
 * again at another. One open cart per (customer, branch), enforced by a
 * partial unique index rather than by hope.
 */
@Entity
@Table(name = "ECOM_CART", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCart extends BaseEntity {

    public static final String OPEN        = "OPEN";
    public static final String CHECKED_OUT = "CHECKED_OUT";
    public static final String ABANDONED   = "ABANDONED";

    @Id
    @Column(name = "PID_CART")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_cart")
    @SequenceGenerator(name = "seq_ecom_cart", sequenceName = "SEQ_ECOM_CART", allocationSize = 1)
    private Long pidCart;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "CART_STATUS", length = 20, nullable = false)
    @Builder.Default
    private String cartStatus = OPEN;

    @Column(name = "COUPON_CODE", length = 40)
    private String couponCode;

    @Column(name = "NOTE", length = 300)
    private String note;

    @Column(name = "CHECKED_OUT_AT")
    private LocalDateTime checkedOutAt;

    @Column(name = "PID_ECOM_ORDER")
    private Long pidEcomOrder;

    public boolean isOpen() { return OPEN.equals(cartStatus); }
}
