package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a POS shopping cart (held order).
 * A cart becomes a POS_TRANSACTION_MST record when finalised.
 * STAGE: OPEN → HELD → FINALISED | CANCELLED
 */
@Entity
@Table(name = "POS_CART",
        indexes = {
            @Index(name = "IDX_CART_USER",    columnList = "PID_USER"),
            @Index(name = "IDX_CART_CUST",    columnList = "PID_CUSTOMER"),
            @Index(name = "IDX_CART_STAGE",   columnList = "STAGE")
        })
@NoArgsConstructor @AllArgsConstructor @Builder
public class PosCart extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_POS_CART")
    @SequenceGenerator(name = "SQ_POS_CART", sequenceName = "SQ_POS_CART", allocationSize = 1)
    @Column(name = "PID_CART")
    private Long pidCart;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_USER", nullable = false)
    private Long pidUser;

    /** Optional: customer can be selected after scanning items */
    @Column(name = "PID_CUSTOMER")
    private Long pidCustomer;

    /**
     * OPEN      – active cart being built
     * HELD      – parked for later recall
     * FINALISED – converted to POS_TRANSACTION_MST
     * CANCELLED – discarded
     */
    @Column(name = "STAGE", nullable = false, length = 20)
    @Builder.Default
    private String stage = "OPEN";

    @Column(name = "HOLD_NOTE", length = 200)
    private String holdNote;

    @Column(name = "DISCOUNT_AMOUNT", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "ADDITIONAL_DISCOUNT_PCT", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal additionalDiscountPct = BigDecimal.ZERO;

    @Column(name = "SUB_TOTAL", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal subTotal = BigDecimal.ZERO;

    @Column(name = "GRAND_TOTAL", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal grandTotal = BigDecimal.ZERO;

    /** Unique token shown on receipt for recall */
    @Column(name = "CART_REF", length = 30, unique = true)
    private String cartRef;

    @Column(name = "FINALISED_AT")
    private LocalDateTime finalisedAt;

    /** Set when cart is converted to a transaction */
    @Column(name = "PID_TRANSACTION")
    private Long pidTransaction;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PosCartItem> items = new ArrayList<>();

    // ── Getters and Setters ──────────────────────────────────────
    public Long getPidCart() { return pidCart; }
    public void setPidCart(Long pidCart) { this.pidCart = pidCart; }

    public Long getPidOrg() { return pidOrg; }
    public void setPidOrg(Long pidOrg) { this.pidOrg = pidOrg; }

    public Long getPidBranch() { return pidBranch; }
    public void setPidBranch(Long pidBranch) { this.pidBranch = pidBranch; }

    public Long getPidUser() { return pidUser; }
    public void setPidUser(Long pidUser) { this.pidUser = pidUser; }

    public Long getPidCustomer() { return pidCustomer; }
    public void setPidCustomer(Long pidCustomer) { this.pidCustomer = pidCustomer; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getHoldNote() { return holdNote; }
    public void setHoldNote(String holdNote) { this.holdNote = holdNote; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getAdditionalDiscountPct() { return additionalDiscountPct; }
    public void setAdditionalDiscountPct(BigDecimal additionalDiscountPct) { this.additionalDiscountPct = additionalDiscountPct; }

    public BigDecimal getSubTotal() { return subTotal; }
    public void setSubTotal(BigDecimal subTotal) { this.subTotal = subTotal; }

    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }

    public String getCartRef() { return cartRef; }
    public void setCartRef(String cartRef) { this.cartRef = cartRef; }

    public LocalDateTime getFinalisedAt() { return finalisedAt; }
    public void setFinalisedAt(LocalDateTime finalisedAt) { this.finalisedAt = finalisedAt; }

    public Long getPidTransaction() { return pidTransaction; }
    public void setPidTransaction(Long pidTransaction) { this.pidTransaction = pidTransaction; }

    public List<PosCartItem> getItems() { return items; }
    public void setItems(List<PosCartItem> items) { this.items = items; }
}
