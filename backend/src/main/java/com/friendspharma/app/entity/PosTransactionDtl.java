package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * POS_TRANSACTION_DTL — detail lines for any POS_TRANSACTION_MST.
 * FEFO procedure inserts rows here with expiry date + sales price per unit mode.
 */
@Entity
@Table(name = "POS_TRANSACTION_DTL", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosTransactionDtl {

    @Id @Column(name = "PID_TRAN_DTL")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_tran_dtl")
    @SequenceGenerator(name = "seq_tran_dtl", sequenceName = "SEQ_POS_TRANSACTION_DTL", allocationSize = 1)
    private Long pidTranDtl;

    @Column(name = "PID_TRAN_MST", nullable = false) private Long pidTranMst;
    @Column(name = "PID_PRODUCT",  nullable = false) private Long pidProduct;
    @Column(name = "QUANTITY",     nullable = false) private BigDecimal quantity;
    @Column(name = "MNP_PRICE",    precision = 14, scale = 4) private BigDecimal mnpPrice;
    @Column(name = "MRP_PRICE",    precision = 14, scale = 4) private BigDecimal mrpPrice;
    @Column(name = "DISCOUNT_PCT", precision = 8,  scale = 4) private BigDecimal discountPct;
    @Column(name = "FLAT_DISCOUNT",precision = 14, scale = 4) private BigDecimal flatDiscount;
    @Column(name = "TOTAL_PRICE",  precision = 14, scale = 4) private BigDecimal totalPrice;
    @Column(name = "EXPIRY_DATE")  private LocalDate expiryDate;
    @Column(name = "SALES_PRICE",  precision = 14, scale = 4) private BigDecimal salesPrice;
    @Column(name = "TP_PCT",       precision = 8,  scale = 4) private BigDecimal tpPct;
    /** [V5] IGTA snapshot from the product — input to the MNP total-price formula. */
    @Column(name = "IGTA",         precision = 14, scale = 4) private BigDecimal igta;
    /** [V5] Pieces per SALES_TYPE unit, derived from the product Box/Leaf at entry. */
    @Column(name = "NO_OF_PCS")    private Integer noOfPcs;
    @Column(name = "SALES_UNIT",   length = 15)  private String salesUnit;
    @Column(name = "STRIP_BOX")    private BigDecimal stripBox;
    @Column(name = "SALES_TYPE",   length = 15)  private String salesType; // BOX/STRIP/PCS
    @Column(name = "PID_TRAN_DTL_RETURN") private Long pidTranDtlReturn;
    @Column(name = "RETURN_QTY")   private BigDecimal returnQty;

    /**
     * [V67] Free units the supplier gave with this line — "buy 10, get 12".
     *
     * <p>Adds to what reaches the shelf and therefore lowers the cost of every
     * piece in the batch. It never enters a money total: the line is priced for
     * what was bought, and the bonus is what makes that price a good one.
     */
    @Builder.Default
    @Column(name = "BONUS_QTY")    private BigDecimal bonusQty = BigDecimal.ZERO;

    /**
     * [V69] PURCHASE or GIFT.
     *
     * <p>A GIFT line is a different product given free with the order: quantity
     * zero, bonus more than zero, no money of its own. It is still received into
     * its own batch and still carries a cost — an allocated share of what the
     * invoice as a whole cost — because a product valued at nothing reports an
     * infinite margin.
     */
    @Builder.Default
    @Column(name = "LINE_KIND", length = 10) private String lineKind = "PURCHASE";
    @Column(name = "INSERTED_DATE") private LocalDateTime insertedDate;
    // [NEW]
    @Column(name = "BATCH_NO",    length = 50) private String batchNo;
    @Column(name = "UNIT_MODE",   length = 10) private String unitMode;

    @PrePersist
    void prePersist() { if (insertedDate == null) insertedDate = LocalDateTime.now(); }
}
