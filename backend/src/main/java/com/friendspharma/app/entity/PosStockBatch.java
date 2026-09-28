package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * POS_STOCK_BATCH — branch-wise, batch-wise stock on hand, held in PIECES.
 *
 * <p>Grain: {@code (pidBranch, pidProduct, batchNo)}. The batch is the unit of
 * stock, not the expiry date. Two deliveries that happen to share an expiry are
 * two batches with two costs; the legacy {@code VW_PRODUCT_STOCK} grouped on
 * expiry and merged them, which destroys the per-batch cost that actual-profit
 * reporting is built on.
 *
 * <p>Every quantity here is pieces. Boxes and strips are presentation units,
 * derived on read from {@link #pcsPerBox} / {@link #pcsPerStrip}. Storing one
 * unit is what keeps the arithmetic exact — no rounding ever enters a balance.
 *
 * <p>This replaces {@code POS_PRODUCT_INFO.STOCK_QTY}, which had no branch, no
 * batch and no history.
 */
@Entity
@Table(name = "POS_STOCK_BATCH", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosStockBatch extends BaseEntity {

    /** Active — issuable. */
    public static final String ACTIVE    = "Y";
    /** Exhausted — kept for history and cost lookup, never allocated. */
    public static final String EXHAUSTED = "X";
    /** Blocked — recalled or quarantined. Present, counted, never issued. */
    public static final String BLOCKED   = "B";

    @Id
    @Column(name = "PID_STOCK")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_stock_batch")
    @SequenceGenerator(name = "seq_stock_batch", sequenceName = "SEQ_POS_STOCK_BATCH", allocationSize = 1)
    private Long pidStock;

    @Column(name = "PID_ORG",     nullable = false) private Long pidOrg;
    @Column(name = "PID_BRANCH",  nullable = false) private Long pidBranch;
    @Column(name = "PID_PRODUCT", nullable = false) private Long pidProduct;

    @Column(name = "BATCH_NO", length = 50, nullable = false) private String batchNo;
    @Column(name = "EXPIRY_DATE") private LocalDate expiryDate;

    /** On hand, in pieces. Maintained transactionally; rebuildable from movements. */
    @Column(name = "QTY_PCS",      precision = 18, scale = 4, nullable = false) private BigDecimal qtyPcs;
    @Column(name = "RECEIVED_PCS", precision = 18, scale = 4, nullable = false) private BigDecimal receivedPcs;
    @Column(name = "ISSUED_PCS",   precision = 18, scale = 4, nullable = false) private BigDecimal issuedPcs;

    /**
     * Landed cost of one piece of this batch — the whole reason batches are
     * tracked apart. COGS is per batch, never per product.
     */
    @Column(name = "COST_PER_PCS", precision = 18, scale = 6) private BigDecimal costPerPcs;
    @Column(name = "MRP_PRICE",    precision = 14, scale = 4) private BigDecimal mrpPrice;

    /**
     * Conversion snapshot frozen at receipt. Deliberately not read live from
     * Box/Leaf: editing a product's pack size later would otherwise silently
     * re-value stock that was received under the old packing.
     */
    @Column(name = "PCS_PER_BOX")   private Integer pcsPerBox;
    @Column(name = "PCS_PER_STRIP") private Integer pcsPerStrip;

    @Column(name = "RECEIVED_DATE", nullable = false) private LocalDate receivedDate;

    @Column(name = "SOURCE_TRAN_MST") private Long sourceTranMst;
    @Column(name = "SOURCE_TRAN_DTL") private Long sourceTranDtl;

    /** True when this lot can be allocated to a sale. */
    @Transient
    public boolean isIssuable() {
        return ACTIVE.equals(getStatus())
                && qtyPcs != null
                && qtyPcs.compareTo(BigDecimal.ZERO) > 0;
    }
}
