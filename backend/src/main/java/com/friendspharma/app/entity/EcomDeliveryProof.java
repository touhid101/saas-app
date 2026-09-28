package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Evidence that an online order was handed over —
 * {@code PHARMA.ECOM_DELIVERY_PROOF} (V109). One row per order.
 *
 * <p>An OTP kind is deliberately absent: it needs an SMS provider nobody has
 * chosen, and a proof kind the system cannot actually produce would be a
 * promise on a screen rather than evidence at a door.
 */
@Entity
@Table(name = "ECOM_DELIVERY_PROOF", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomDeliveryProof extends com.friendspharma.app.entity.base.BaseEntity {

    /** A photograph taken at the door. */
    public static final String PHOTO = "PHOTO";
    /** The name of whoever took it, written down. */
    public static final String NAME = "NAME";
    /** Neither: what the driver wrote about the handover. */
    public static final String NOTE = "NOTE";

    @Id
    @Column(name = "PID_PROOF")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_delivery_proof")
    @SequenceGenerator(name = "seq_ecom_delivery_proof", sequenceName = "SEQ_ECOM_DELIVERY_PROOF", allocationSize = 1)
    private Long pidProof;

    @Column(name = "PID_ECOM_ORDER", nullable = false) private Long pidEcomOrder;
    @Column(name = "PID_BRANCH", nullable = false)     private Long pidBranch;
    @Column(name = "PID_EMPLOYEE")                     private Long pidEmployee;

    @Column(name = "PROOF_KIND", length = 20, nullable = false) private String proofKind;
    @Column(name = "RECEIVED_BY_NAME", length = 150) private String receivedByName;
    @Column(name = "PHOTO_URL", length = 300)        private String photoUrl;
    @Column(name = "NOTE", length = 300)             private String note;

    /** Where the handover happened, when the device knew. */
    @Column(name = "LATITUDE", precision = 9, scale = 6)  private BigDecimal latitude;
    @Column(name = "LONGITUDE", precision = 9, scale = 6) private BigDecimal longitude;

    @Column(name = "CAPTURED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime capturedAt = LocalDateTime.now();

    @Column(name = "CAPTURED_BY", length = 50) private String capturedBy;
}
