package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * [V71] One Super Admin password reset, kept whether or not it succeeded.
 *
 * <p>Names are stored beside the ids on purpose. This is the table a dispute
 * about an account takeover is settled from, and a row reading "user 47 in
 * branch 3" stops answering that question the moment either is renamed or
 * deleted. The ids are still here for joining while the rows exist; the names
 * are what survives them.
 *
 * <p>Refusals are recorded as well as resets. Repeated refusals against one
 * account are the shape an attack has, and a table holding only successes
 * cannot show it.
 */
@Entity
@Table(name = "ACL_PASSWORD_RESET_AUDIT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AclPasswordResetAudit {

    @Id @Column(name = "PID_AUDIT")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pidAudit;

    @Column(name = "PID_USER",  nullable = false) private Long   pidUser;
    @Column(name = "USER_NAME", nullable = false, length = 50)  private String userName;
    @Column(name = "FULL_NAME", length = 100) private String fullName;

    @Column(name = "PID_BRANCH")  private Long   pidBranch;
    @Column(name = "BRANCH_NAME", length = 150) private String branchName;
    @Column(name = "PID_ORG")     private Long   pidOrg;

    @Column(name = "RESET_BY_USER", nullable = false) private Long   resetByUser;
    @Column(name = "RESET_BY_NAME", nullable = false, length = 50) private String resetByName;

    @Column(name = "RESET_AT", nullable = false) private LocalDateTime resetAt;
    @Column(name = "IP_ADDRESS", length = 45) private String ipAddress;

    /** RESET, or a refusal: REFUSED_SELF, REFUSED_PEER, REFUSED_NOT_FOUND. */
    @Column(name = "OUTCOME", nullable = false, length = 20) private String outcome;
    @Column(name = "REASON", length = 200) private String reason;

    @PrePersist
    void prePersist() { if (resetAt == null) resetAt = LocalDateTime.now(); }
}
