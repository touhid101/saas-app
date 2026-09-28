package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Every change of a customer's pricing tier — {@code PHARMA.ECOM_CUSTOMER_TYPE_HISTORY} (V100). Append-only. */
@Entity
@Table(name = "ECOM_CUSTOMER_TYPE_HISTORY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCustomerTypeHistory {

    @Id
    @Column(name = "PID_HISTORY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_customer_type_history")
    @SequenceGenerator(name = "seq_ecom_customer_type_history", sequenceName = "SEQ_ECOM_CUSTOMER_TYPE_HISTORY", allocationSize = 1)
    private Long pidHistory;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    @Column(name = "OLD_TYPE", length = 20)
    private String oldType;

    @Column(name = "NEW_TYPE", length = 20, nullable = false)
    private String newType;

    @Column(name = "CHANGED_BY", length = 50, nullable = false)
    private String changedBy;

    @Column(name = "REASON", length = 300)
    private String reason;

    @Column(name = "CHANGED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime changedAt = LocalDateTime.now();
}
