package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_journal — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_JOURNAL", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccJournal extends BaseEntity {
    @Id @Column(name = "PID_JOURNAL")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_journal")
    @SequenceGenerator(name = "seq_acc_journal", sequenceName = "SEQ_ACC_JOURNAL", allocationSize = 1)
    private Long pidJournal;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "JOURNAL_NO", length = 30)
    private String journalNo;
    @Column(name = "JOURNAL_DATE")
    private LocalDate journalDate;
    @Column(name = "PID_PERIOD")
    private Long pidPeriod;
    @Column(name = "JOURNAL_TYPE", length = 12)
    private String journalType;
    @Column(name = "SOURCE_MODULE", length = 30)
    private String sourceModule;
    @Column(name = "SOURCE_TYPE", length = 40)
    private String sourceType;
    @Column(name = "SOURCE_ID")
    private Long sourceId;
    @Column(name = "PID_EVENT")
    private Long pidEvent;
    @Column(name = "REFERENCE", length = 60)
    private String reference;
    @Column(name = "DESCRIPTION", length = 300)
    private String description;
    @Column(name = "PID_COST_CENTER")
    private Long pidCostCenter;
    @Column(name = "CURRENCY", length = 3)
    @Builder.Default private String currency = "BDT";
    @Column(name = "TOTAL_DEBIT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal totalDebit = BigDecimal.ZERO;
    @Column(name = "TOTAL_CREDIT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal totalCredit = BigDecimal.ZERO;
    @Column(name = "JOURNAL_STATUS", length = 10)
    @Builder.Default private String journalStatus = "DRAFT";
    @Column(name = "REVERSAL_OF")
    private Long reversalOf;
    @Column(name = "REVERSED_BY")
    private Long reversedBy;
    @Column(name = "REVERSAL_REASON", length = 500)
    private String reversalReason;
    @Column(name = "APPROVED_BY", length = 50)
    private String approvedBy;
    @Column(name = "APPROVED_AT")
    private LocalDateTime approvedAt;
    @Column(name = "POSTED_BY", length = 50)
    private String postedBy;
    @Column(name = "POSTED_AT")
    private LocalDateTime postedAt;
    @Column(name = "REVERSED_BY_USER", length = 50)
    private String reversedByUser;
    @Column(name = "REVERSED_AT")
    private LocalDateTime reversedAt;
}
