package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A customer session — {@code PHARMA.ECOM_CUSTOMER_REFRESH_TOKEN} (V100).
 *
 * <p>Mirrors {@link PosRefreshToken}, whose {@code PID_USER} means a staff
 * account; reusing that table would have made a customer look like one.
 */
@Entity
@Table(name = "ECOM_CUSTOMER_REFRESH_TOKEN", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCustomerRefreshToken {

    @Id
    @Column(name = "PID_TOKEN")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_customer_refresh_token")
    @SequenceGenerator(name = "seq_ecom_customer_refresh_token", sequenceName = "SEQ_ECOM_CUSTOMER_REFRESH_TOKEN", allocationSize = 1)
    private Long pidToken;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    @Column(name = "TOKEN_VALUE", length = 512, nullable = false, unique = true)
    private String tokenValue;

    @Column(name = "EXPIRES_AT", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "REVOKED", length = 1, nullable = false)
    @Builder.Default
    private String revoked = "N";

    @Column(name = "CREATED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
