package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What is owed for one billing period.
 *
 * <p>Settling one is what reactivates a branch, which is why payment goes through
 * an invoice rather than straight onto the subscription: a gateway webhook and
 * the Super Admin screen then post the same transition against the same row.
 */
@Entity
@Table(name = "SUB_INVOICE")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubInvoice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SUB_INVOICE")
    @SequenceGenerator(name = "SQ_SUB_INVOICE", sequenceName = "PHARMA.SQ_SUB_INVOICE", allocationSize = 1)
    @Column(name = "PID_INVOICE")
    private Long pidInvoice;

    @Column(name = "PID_SUBSCRIPTION", nullable = false)
    private Long pidSubscription;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "INVOICE_NO", nullable = false, length = 30)
    private String invoiceNo;

    @Column(name = "PERIOD_FROM", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "PERIOD_TO", nullable = false)
    private LocalDate periodTo;

    @Column(name = "AMOUNT", nullable = false)
    private BigDecimal amount;

    @Column(name = "ISSUED_ON", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "DUE_ON", nullable = false)
    private LocalDate dueOn;

    /** PENDING, PAID or VOID. */
    @Column(name = "PAY_STATUS", nullable = false, length = 10)
    private String payStatus;

}