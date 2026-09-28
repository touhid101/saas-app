package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One source invoice that was merged into a PosInvoiceMerge.
 */
@Entity
@Table(name = "POS_INVOICE_MERGE_DTL",
        indexes = @Index(name = "IDX_MRGDTL_MST", columnList = "PID_MERGE"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosInvoiceMergeDtl extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_INV_MERGE_DTL")
    @SequenceGenerator(name = "SQ_INV_MERGE_DTL", sequenceName = "SQ_INV_MERGE_DTL", allocationSize = 1)
    @Column(name = "PID_MERGE_DTL")
    private Long pidMergeDtl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PID_MERGE", nullable = false)
    private PosInvoiceMerge merge;

    /** FK → POS_TRANSACTION_MST */
    @Column(name = "PID_TRANSACTION", nullable = false)
    private Long pidTransaction;

    @Column(name = "INVOICE_NO", length = 50)
    private String invoiceNo;

    @Column(name = "INVOICE_AMOUNT", precision = 15, scale = 2)
    private BigDecimal invoiceAmount;
}
