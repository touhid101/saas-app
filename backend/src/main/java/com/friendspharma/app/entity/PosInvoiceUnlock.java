package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One supervisor authorisation to reopen an invoice.
 *
 * <p>The row is both the mechanism and the record. It is written when a
 * supervisor approves, carries the (hashed) token that lets the edit through,
 * and is stamped again when that token is spent — so the audit shows not just
 * who allowed what, but whether the edit they allowed ever happened.
 */
@Entity
@Table(name = "POS_INVOICE_UNLOCK")
@Getter
@Setter
@NoArgsConstructor
public class PosInvoiceUnlock {

    public static final String ACTIVE = "Y";
    public static final String CANCELLED = "C";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_INVOICE_UNLOCK")
    @SequenceGenerator(name = "SQ_INVOICE_UNLOCK", sequenceName = "PHARMA.SQ_INVOICE_UNLOCK", allocationSize = 1)
    @Column(name = "PID_UNLOCK")
    private Long pidUnlock;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "PID_TRAN_MST", nullable = false)
    private Long pidTranMst;

    @Column(name = "INVOICE_NO", length = 50)
    private String invoiceNo;

    @Column(name = "REQUESTED_BY", nullable = false)
    private Long requestedBy;

    @Column(name = "REQUESTED_BY_NAME", length = 100)
    private String requestedByName;

    @Column(name = "AUTHORISED_BY", nullable = false)
    private Long authorisedBy;

    @Column(name = "AUTHORISED_BY_NAME", length = 100)
    private String authorisedByName;

    @Column(name = "REASON", length = 255)
    private String reason;

    /**
     * SHA-256 of the token, never the token.
     *
     * <p>The server only ever compares hashes, so a leaked copy of this table
     * cannot be used to reopen anything.
     */
    @Column(name = "TOKEN_HASH", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "ISSUED_AT", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "EXPIRES_AT", nullable = false)
    private LocalDateTime expiresAt;

    /** Stamped when spent. One authorisation permits exactly one edit. */
    @Column(name = "USED_AT")
    private LocalDateTime usedAt;

    @Column(name = "STATUS", nullable = false, length = 1)
    private String status = ACTIVE;

    @Column(name = "INSERTED_BY", length = 50)
    private String insertedBy;

    @Column(name = "INSERTED_DATE")
    private LocalDateTime insertedDate;

    @Column(name = "UPDATED_BY", length = 50)
    private String updatedBy;

    @Column(name = "UPDATED_DATE")
    private LocalDateTime updatedDate;
}
