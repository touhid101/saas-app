package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A pharmacy waiting to be approved.
 *
 * <p>Nothing here is live. The row holds what the applicant typed and the hashed
 * password they chose; approving it is what creates the branch, the subscription
 * and the account. Until then a registration can create nothing and reach
 * nothing, which is the point of having a queue rather than a sign-up.
 */
@Entity
@Table(name = "TENANT_REGISTRATION")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantRegistration extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_REGISTRATION")
    @SequenceGenerator(name = "SQ_REGISTRATION", sequenceName = "PHARMA.SQ_REGISTRATION", allocationSize = 1)
    @Column(name = "PID_REGISTRATION")
    private Long pidRegistration;

    @Column(name = "PHARMACY_NAME", nullable = false, length = 150)
    private String pharmacyName;

    @Column(name = "CONTACT_NAME", nullable = false, length = 100)
    private String contactName;

    @Column(name = "CONTACT_EMAIL", length = 120)
    private String contactEmail;

    @Column(name = "CONTACT_PHONE", nullable = false, length = 30)
    private String contactPhone;

    @Column(name = "ADDRESS", length = 300)
    private String address;

    @Column(name = "ADMIN_USERNAME", nullable = false, length = 50)
    private String adminUsername;

    /** Hashed on arrival. A pending application never holds a readable password. */
    @Column(name = "ADMIN_PASSWORD", nullable = false, length = 500)
    private String adminPassword;

    @Column(name = "REG_STATUS", nullable = false, length = 20)
    private String regStatus;

    @Column(name = "REJECT_REASON", length = 300)
    private String rejectReason;

    /**
     * What the applicant asked for, if the sign-up page offered it.
     *
     * <p>A preference, not a decision. The Super Admin still chooses what a
     * pharmacy is activated on — this only spares them retyping what the
     * applicant already said, and gives the sales conversation a starting point.
     * Held as the plan's code rather than its id so a plan being retired and
     * re-created does not silently repoint an old application at a new price.
     */
    @Column(name = "PREFERRED_PLAN_CODE", length = 30)
    private String preferredPlanCode;

    @Column(name = "PREFERRED_TERM_MONTHS")
    private Integer preferredTermMonths;

    /** Filled in on approval, so the queue row points at what it became. */
    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "PID_USER")
    private Long pidUser;

    @Column(name = "REVIEWED_BY", length = 50)
    private String reviewedBy;

    @Column(name = "REVIEWED_AT")
    private LocalDateTime reviewedAt;

}