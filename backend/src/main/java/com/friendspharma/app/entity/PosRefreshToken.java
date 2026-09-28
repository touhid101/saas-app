package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * JWT refresh token storage — POS_REFRESH_TOKENS [NEW].
 */
@Entity
@Table(name = "POS_REFRESH_TOKENS", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosRefreshToken {

    @Id
    @Column(name = "PID_TOKEN")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_refresh_tok")
    @SequenceGenerator(name = "seq_refresh_tok", sequenceName = "SEQ_POS_REFRESH_TOKENS", allocationSize = 1)
    private Long pidToken;

    @Column(name = "PID_USER", nullable = false)
    private Long pidUser;

    @Column(name = "TOKEN_VALUE", length = 512, nullable = false, unique = true)
    private String tokenValue;

    @Column(name = "EXPIRES_AT", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "REVOKED", length = 1)
    @Builder.Default
    private String revoked = "N";

    @Column(name = "CREATED_AT")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
