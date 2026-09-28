package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_backfill_batch — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_BACKFILL_BATCH", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccBackfillBatch extends BaseEntity {
    @Id @Column(name = "PID_BACKFILL_BATCH")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_backfill_batch")
    @SequenceGenerator(name = "seq_acc_backfill_batch", sequenceName = "SEQ_ACC_BACKFILL_BATCH", allocationSize = 1)
    private Long pidBackfillBatch;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "SCOPE_MODULE", length = 30)
    private String scopeModule;
    @Column(name = "DATE_FROM")
    private LocalDate dateFrom;
    @Column(name = "DATE_TO")
    private LocalDate dateTo;
    @Column(name = "DRY_RUN")
    @Builder.Default private Integer dryRun = 0;
    @Column(name = "BATCH_STATUS", length = 12)
    @Builder.Default private String batchStatus = "RUNNING";
    @Column(name = "SCANNED_COUNT")
    @Builder.Default private Integer scannedCount = 0;
    @Column(name = "POSTED_COUNT")
    @Builder.Default private Integer postedCount = 0;
    @Column(name = "SKIPPED_COUNT")
    @Builder.Default private Integer skippedCount = 0;
    @Column(name = "FAILED_COUNT")
    @Builder.Default private Integer failedCount = 0;
    @Column(name = "SUMMARY", columnDefinition = "TEXT")
    private String summary;
    @Column(name = "STARTED_BY", length = 50)
    private String startedBy;
    @Column(name = "STARTED_AT")
    private LocalDateTime startedAt;
    @Column(name = "FINISHED_AT")
    private LocalDateTime finishedAt;
}
