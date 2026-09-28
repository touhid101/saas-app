package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One position reported by a driver's device while an order is on its way —
 * {@code PHARMA.ECOM_DELIVERY_TRACKING} (V109). Append-only.
 *
 * <p>Written only between dispatch and delivery, and the customer is shown the
 * most recent row rather than the sequence: how far away their medicine is is
 * their business, where the driver has been is not.
 */
@Entity
@Table(name = "ECOM_DELIVERY_TRACKING", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomDeliveryTracking extends com.friendspharma.app.entity.base.BaseEntity {

    @Id
    @Column(name = "PID_TRACKING")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_delivery_tracking")
    @SequenceGenerator(name = "seq_ecom_delivery_tracking", sequenceName = "SEQ_ECOM_DELIVERY_TRACKING", allocationSize = 1)
    private Long pidTracking;

    @Column(name = "PID_ECOM_ORDER", nullable = false) private Long pidEcomOrder;
    @Column(name = "PID_BRANCH", nullable = false)     private Long pidBranch;
    @Column(name = "PID_EMPLOYEE")                     private Long pidEmployee;

    @Column(name = "LATITUDE", precision = 9, scale = 6, nullable = false)  private BigDecimal latitude;
    @Column(name = "LONGITUDE", precision = 9, scale = 6, nullable = false) private BigDecimal longitude;

    /** What the device claimed about the reading, in metres. */
    @Column(name = "ACCURACY_M", precision = 8, scale = 1) private BigDecimal accuracyM;

    @Column(name = "RECORDED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime recordedAt = LocalDateTime.now();
}
