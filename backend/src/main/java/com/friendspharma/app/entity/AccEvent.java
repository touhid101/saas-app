package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_event — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_EVENT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccEvent extends BaseEntity {
    @Id @Column(name = "PID_EVENT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_event")
    @SequenceGenerator(name = "seq_acc_event", sequenceName = "SEQ_ACC_EVENT", allocationSize = 1)
    private Long pidEvent;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "SOURCE_MODULE", length = 30)
    private String sourceModule;
    @Column(name = "SOURCE_TYPE", length = 40)
    private String sourceType;
    @Column(name = "SOURCE_ID")
    private Long sourceId;
    @Column(name = "EVENT_KEY", length = 40)
    private String eventKey;
    @Column(name = "EVENT_TYPE", length = 40)
    private String eventType;
    @Column(name = "EVENT_DATE")
    private LocalDate eventDate;
    @Column(name = "REFERENCE", length = 60)
    private String reference;
    @Column(name = "DESCRIPTION", length = 300)
    private String description;
    @Column(name = "AMOUNT", precision = 18, scale = 2)
    private BigDecimal amount;
    @Column(name = "PAYLOAD", columnDefinition = "TEXT")
    private String payload;
    @Column(name = "EVENT_STATUS", length = 12)
    @Builder.Default private String eventStatus = "PENDING";
    @Column(name = "PID_JOURNAL")
    private Long pidJournal;
    @Column(name = "ERROR_MESSAGE", length = 1000)
    private String errorMessage;
    @Column(name = "RETRY_COUNT")
    @Builder.Default private Integer retryCount = 0;
    @Column(name = "PROCESSED_AT")
    private LocalDateTime processedAt;
    @Column(name = "PID_BACKFILL_BATCH")
    private Long pidBackfillBatch;
}
