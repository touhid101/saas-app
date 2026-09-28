package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * What happened to an e-commerce account — {@code PHARMA.ECOM_AUDIT_LOG} (V100).
 * Written through {@code EcomAuditRecorder} in its own transaction, so a refused
 * action still leaves its trace.
 */
@Entity
@Table(name = "ECOM_AUDIT_LOG", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomAuditLog {

    public static final String ACTOR_CUSTOMER = "CUSTOMER";
    public static final String ACTOR_STAFF    = "STAFF";
    public static final String ACTOR_SYSTEM   = "SYSTEM";

    @Id
    @Column(name = "PID_AUDIT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_audit_log")
    @SequenceGenerator(name = "seq_ecom_audit_log", sequenceName = "SEQ_ECOM_AUDIT_LOG", allocationSize = 1)
    private Long pidAudit;

    @Column(name = "EVENT_TYPE", length = 40, nullable = false)
    private String eventType;

    @Column(name = "ACTOR_KIND", length = 10, nullable = false)
    private String actorKind;

    @Column(name = "ACTOR_ID", length = 80)
    private String actorId;

    @Column(name = "PID_ECOM_CUSTOMER")
    private Long pidEcomCustomer;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "REF_TYPE", length = 30)
    private String refType;

    @Column(name = "REF_ID")
    private Long refId;

    @Column(name = "DETAIL", length = 1000)
    private String detail;

    @Column(name = "IP_ADDRESS", length = 64)
    private String ipAddress;

    @Column(name = "ACTED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime actedAt = LocalDateTime.now();
}
