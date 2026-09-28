package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Money received against an invoice.
 *
 * <p>{@code providerRef} and {@code idempotencyKey} are for the gateway that does
 * not exist yet. A webhook that fires twice must settle an invoice once, and the
 * key is what makes the second call a no-op. The manual path leaves both null.
 */
@Entity
@Table(name = "SUB_PAYMENT")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubPayment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SUB_PAYMENT")
    @SequenceGenerator(name = "SQ_SUB_PAYMENT", sequenceName = "PHARMA.SQ_SUB_PAYMENT", allocationSize = 1)
    @Column(name = "PID_PAYMENT")
    private Long pidPayment;

    @Column(name = "PID_INVOICE", nullable = false)
    private Long pidInvoice;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "AMOUNT", nullable = false)
    private BigDecimal amount;

    @Column(name = "PAID_ON", nullable = false)
    private LocalDate paidOn;

    /** MANUAL, BKASH, SSLCOMMERZ or BANK. */
    @Column(name = "METHOD", nullable = false, length = 20)
    private String method;

    /** The gateway transaction, when there is one. */
    @Column(name = "PROVIDER_REF", length = 100)
    private String providerRef;

    /** Unique where present; makes a repeated webhook a no-op. */
    @Column(name = "IDEMPOTENCY_KEY", length = 100)
    private String idempotencyKey;

    @Column(name = "NOTE", length = 300)
    private String note;

}