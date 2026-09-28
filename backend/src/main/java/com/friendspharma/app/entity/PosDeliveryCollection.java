package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Records a payment collection event against a sales invoice.
 * One invoice may have multiple partial collections (instalment payments).
 * STATUS: PENDING → COLLECTED → CANCELLED
 */
@Entity
@Table(name = "POS_DELIVERY_COLLECTION",
        indexes = {
            @Index(name = "IDX_COLL_TRAN",  columnList = "PID_TRANSACTION"),
            @Index(name = "IDX_COLL_DMAN",  columnList = "PID_DELIVERY_MAN"),
            @Index(name = "IDX_COLL_DATE",  columnList = "COLLECTION_DATE")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosDeliveryCollection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_DELIVERY_COLL")
    @SequenceGenerator(name = "SQ_DELIVERY_COLL", sequenceName = "SQ_DELIVERY_COLL", allocationSize = 1)
    @Column(name = "PID_COLLECTION")
    private Long pidCollection;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    /** FK → POS_TRANSACTION_MST.PID_TRANSACTION */
    @Column(name = "PID_TRANSACTION", nullable = false)
    private Long pidTransaction;

    /** Invoice number — denormalised for fast reporting */
    @Column(name = "INVOICE_NO", length = 50)
    private String invoiceNo;

    /** FK → HrEmployee (delivery man) */
    @Column(name = "PID_DELIVERY_MAN")
    private Long pidDeliveryMan;

    @Column(name = "DELIVERY_MAN_NAME", length = 200)
    private String deliveryManName;

    /** FK → PosCustomerInfo */
    @Column(name = "PID_CUSTOMER")
    private Long pidCustomer;

    @Column(name = "CUSTOMER_NAME", length = 300)
    private String customerName;

    @Column(name = "COLLECTION_DATE", nullable = false)
    private LocalDate collectionDate;

    /** Invoice amount at time of collection */
    @Column(name = "INVOICE_AMOUNT", precision = 15, scale = 2)
    private BigDecimal invoiceAmount;

    /** Amount collected in this event */
    @Column(name = "COLLECTED_AMOUNT", nullable = false, precision = 15, scale = 2)
    private BigDecimal collectedAmount;

    /** Outstanding balance after this collection */
    @Column(name = "BALANCE_AMOUNT", precision = 15, scale = 2)
    private BigDecimal balanceAmount;

    /** CASH | BANK_TRANSFER | CHEQUE | MOBILE_BANKING */
    @Column(name = "PAYMENT_MODE", length = 30)
    @Builder.Default
    private String paymentMode = "CASH";

    @Column(name = "CHEQUE_NO", length = 50)
    private String chequeNo;

    @Column(name = "BANK_NAME", length = 100)
    private String bankName;

    @Column(name = "TRANSACTION_REF", length = 100)
    private String transactionRef;

    @Column(name = "REMARKS", length = 500)
    private String remarks;

    /** PENDING | COLLECTED | CANCELLED */
    @Column(name = "STATUS", nullable = false, length = 20)
    @Builder.Default
    private String status = "COLLECTED";
}
