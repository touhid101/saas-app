package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * How an e-commerce customer signs in — {@code PHARMA.ECOM_CUSTOMER_AUTH} (V100).
 *
 * <p>Separate from {@link AclUser}, which is a member of staff inside one
 * tenant. The hash is written by the shared {@code PasswordEncoder} bean, so it
 * is always {@code {bcrypt}…}; there is no plaintext fallback on this table.
 */
@Entity
@Table(name = "ECOM_CUSTOMER_AUTH", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCustomerAuth extends BaseEntity {

    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_LOCKED   = "LOCKED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @Column(name = "PID_AUTH")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_customer_auth")
    @SequenceGenerator(name = "seq_ecom_customer_auth", sequenceName = "SEQ_ECOM_CUSTOMER_AUTH", allocationSize = 1)
    private Long pidAuth;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    /** The normalised mobile number. Unique on the platform, never per branch. */
    @Column(name = "LOGIN_IDENTIFIER", length = 150, nullable = false)
    private String loginIdentifier;

    @Column(name = "PASSWORD_HASH", length = 255, nullable = false)
    private String passwordHash;

    @Column(name = "AUTH_STATUS", length = 20, nullable = false)
    @Builder.Default
    private String authStatus = STATUS_ACTIVE;

    @Column(name = "FAILED_LOGIN_COUNT", nullable = false)
    @Builder.Default
    private Integer failedLoginCount = 0;

    @Column(name = "LOCKED_UNTIL")
    private LocalDateTime lockedUntil;

    @Column(name = "LAST_LOGIN_AT")
    private LocalDateTime lastLoginAt;

    @Column(name = "PASSWORD_CHANGED_AT")
    private LocalDateTime passwordChangedAt;

    @Column(name = "EMAIL_VERIFIED", length = 1, nullable = false)
    @Builder.Default
    private String emailVerified = "N";

    @Column(name = "PHONE_VERIFIED", length = 1, nullable = false)
    @Builder.Default
    private String phoneVerified = "N";

    public boolean isLockedNow() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }
}
