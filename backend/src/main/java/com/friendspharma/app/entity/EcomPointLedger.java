package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Every point earned, spent, expired or adjusted —
 * {@code PHARMA.ECOM_POINT_LEDGER} (V111). Append-only.
 *
 * <p>This is the truth about a balance. {@code ECOM_CUSTOMER.POINTS_BALANCE} is
 * a cache of its sum, written only by the service that writes a row here, in
 * the same transaction — the rule the ERP already applies to stock and to
 * accounts.
 */
@Entity
@Table(name = "ECOM_POINT_LEDGER", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomPointLedger extends com.friendspharma.app.entity.base.BaseEntity {

    public static final String EARN = "EARN", REDEEM = "REDEEM", EXPIRE = "EXPIRE", ADJUST = "ADJUST";

    @Id
    @Column(name = "PID_POINT_ENTRY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_point_ledger")
    @SequenceGenerator(name = "seq_ecom_point_ledger", sequenceName = "SEQ_ECOM_POINT_LEDGER", allocationSize = 1)
    private Long pidPointEntry;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false) private Long pidEcomCustomer;
    @Column(name = "ENTRY_TYPE", length = 10, nullable = false) private String entryType;

    /** Positive on the way in, negative on the way out. The CHECK enforces it. */
    @Column(name = "POINTS", precision = 12, scale = 2, nullable = false) private BigDecimal points;
    @Column(name = "BALANCE_AFTER", precision = 12, scale = 2, nullable = false) private BigDecimal balanceAfter;
    /** What they were worth in money, on a redemption. */
    @Column(name = "VALUE_AMOUNT", precision = 14, scale = 2) private BigDecimal valueAmount;

    @Column(name = "PID_ECOM_ORDER") private Long pidEcomOrder;
    @Column(name = "PID_BRANCH")     private Long pidBranch;
    @Column(name = "PID_POINT_RULE") private Long pidPointRule;
    @Column(name = "NOTE", length = 300) private String note;

    @Column(name = "CREATED_AT", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "CREATED_BY", length = 50) private String createdBy;
}
