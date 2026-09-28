package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * POS_STOCK_MOVEMENT — append-only log of every piece that entered or left a
 * batch.
 *
 * <p>Never updated, never deleted. A correction is a new opposing row. That is
 * what makes {@link PosStockBatch#getQtyPcs()} verifiable rather than merely
 * believed: the balance is a cache, this is the truth, and the two can be
 * reconciled at any time.
 *
 * <p>Quantity is always positive; {@link #direction} carries the sign. A signed
 * quantity column invites a sign error to silently invert a movement, and a
 * later {@code SUM()} would hide it rather than surface it.
 */
@Entity
@Table(name = "POS_STOCK_MOVEMENT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosStockMovement {

    public static final String IN  = "I";
    public static final String OUT = "O";

    public static final String PURCHASE        = "PURCHASE";
    public static final String SALE            = "SALE";
    public static final String PURCHASE_RETURN = "PURCHASE_RETURN";
    public static final String SALES_RETURN    = "SALES_RETURN";
    public static final String ADJUST          = "ADJUST";
    public static final String OPENING         = "OPENING";
    public static final String TRANSFER_IN     = "TRANSFER_IN";
    public static final String TRANSFER_OUT    = "TRANSFER_OUT";

    @Id
    @Column(name = "PID_MOVEMENT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_stock_movement")
    @SequenceGenerator(name = "seq_stock_movement", sequenceName = "SEQ_POS_STOCK_MOVEMENT", allocationSize = 1)
    private Long pidMovement;

    @Column(name = "PID_STOCK",   nullable = false) private Long pidStock;
    @Column(name = "PID_ORG",     nullable = false) private Long pidOrg;
    @Column(name = "PID_BRANCH",  nullable = false) private Long pidBranch;
    @Column(name = "PID_PRODUCT", nullable = false) private Long pidProduct;
    @Column(name = "BATCH_NO", length = 50, nullable = false) private String batchNo;

    @Column(name = "MOVEMENT_TYPE", length = 20, nullable = false) private String movementType;
    @Column(name = "DIRECTION",     length = 1,  nullable = false) private String direction;

    /** Always positive, in pieces. */
    @Column(name = "QTY_PCS",       precision = 18, scale = 4, nullable = false) private BigDecimal qtyPcs;
    /** Batch balance immediately after this movement — makes the log self-checking. */
    @Column(name = "BALANCE_AFTER", precision = 18, scale = 4, nullable = false) private BigDecimal balanceAfter;
    @Column(name = "COST_PER_PCS",  precision = 18, scale = 6) private BigDecimal costPerPcs;

    @Column(name = "REF_TYPE", length = 20) private String refType;
    @Column(name = "REF_MST") private Long refMst;
    /**
     * The transaction detail line that caused this movement. Carries a partial
     * unique index with REF_TYPE and MOVEMENT_TYPE, so re-approving an invoice
     * cannot double-count it.
     */
    @Column(name = "REF_DTL") private Long refDtl;

    /** Business date, not insert time — reports are cut on this. */
    @Column(name = "MOVEMENT_DATE", nullable = false) private LocalDate movementDate;
    @Column(name = "REMARKS", length = 255) private String remarks;

    @Column(name = "INSERTED_DATE") private LocalDateTime insertedDate;
    @Column(name = "INSERTED_BY", length = 50) private String insertedBy;

    @PrePersist
    void prePersist() {
        if (insertedDate == null) insertedDate = LocalDateTime.now();
        if (movementDate == null) movementDate = LocalDate.now();
    }

    /** Signed contribution to the batch balance. */
    @Transient
    public BigDecimal signedQty() {
        return OUT.equals(direction) ? qtyPcs.negate() : qtyPcs;
    }
}
