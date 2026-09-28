package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * System / user notifications.
 * TYPE: LOW_STOCK | EXPIRY_ALERT | PENDING_APPROVAL | DELIVERY_UPDATE |
 *        COLLECTION_DUE | SYSTEM | AI_RECOMMENDATION
 */
@Entity
@Table(name = "POS_NOTIFICATION",
        indexes = {
            @Index(name = "IDX_NOTIF_USER",  columnList = "PID_USER"),
            @Index(name = "IDX_NOTIF_READ",  columnList = "IS_READ"),
            @Index(name = "IDX_NOTIF_ORG",   columnList = "PID_ORG")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosNotification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_POS_NOTIF")
    @SequenceGenerator(name = "SQ_POS_NOTIF", sequenceName = "SQ_POS_NOTIF", allocationSize = 1)
    @Column(name = "PID_NOTIFICATION")
    private Long pidNotification;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    /** The pharmacy this row belongs to. Added with V30; every row has one. */
    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    /** null = broadcast to all users in org; set = targeted at one user */
    @Column(name = "PID_USER")
    private Long pidUser;

    /** LOW_STOCK | EXPIRY_ALERT | PENDING_APPROVAL | DELIVERY_UPDATE |
     *  COLLECTION_DUE | SYSTEM | AI_RECOMMENDATION */
    @Column(name = "NOTIF_TYPE", nullable = false, length = 30)
    private String notifType;

    @Column(name = "TITLE", nullable = false, length = 200)
    private String title;

    @Column(name = "MESSAGE", nullable = false, length = 1000)
    private String message;

    /** INFO | WARNING | ERROR | SUCCESS */
    @Column(name = "SEVERITY", length = 10)
    @Builder.Default
    private String severity = "INFO";

    /** Optional link the frontend navigates to on click */
    @Column(name = "ACTION_URL", length = 200)
    private String actionUrl;

    /** Optional reference to the triggering record */
    @Column(name = "REF_ID")
    private Long refId;

    @Column(name = "REF_TYPE", length = 50)
    private String refType;

    @Column(name = "IS_READ", nullable = false)
    @Builder.Default
    private Integer isRead = 0;
}
