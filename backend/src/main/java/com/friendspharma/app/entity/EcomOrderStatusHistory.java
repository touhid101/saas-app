package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Every move an order made, who made it and why — {@code PHARMA.ECOM_ORDER_STATUS_HISTORY} (V105). Append-only. */
@Entity
@Table(name = "ECOM_ORDER_STATUS_HISTORY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomOrderStatusHistory {

    @Id
    @Column(name = "PID_HISTORY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_order_history")
    @SequenceGenerator(name = "seq_ecom_order_history", sequenceName = "SEQ_ECOM_ORDER_HISTORY", allocationSize = 1)
    private Long pidHistory;

    @Column(name = "PID_ECOM_ORDER", nullable = false)
    private Long pidEcomOrder;

    @Column(name = "FROM_STATUS", length = 20) private String fromStatus;
    @Column(name = "TO_STATUS", length = 20, nullable = false) private String toStatus;
    @Column(name = "ACTOR_KIND", length = 10, nullable = false) private String actorKind;
    @Column(name = "ACTOR_ID", length = 80) private String actorId;
    @Column(name = "REASON", length = 300) private String reason;

    @Column(name = "CHANGED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime changedAt = LocalDateTime.now();
}
