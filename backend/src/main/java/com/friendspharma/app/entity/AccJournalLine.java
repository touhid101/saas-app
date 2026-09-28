package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_journal_line — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_JOURNAL_LINE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccJournalLine {
    @Id @Column(name = "PID_JOURNAL_LINE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_journal_line")
    @SequenceGenerator(name = "seq_acc_journal_line", sequenceName = "SEQ_ACC_JOURNAL_LINE", allocationSize = 1)
    private Long pidJournalLine;

    @Column(name = "PID_JOURNAL")
    private Long pidJournal;
    @Column(name = "LINE_NO")
    private Integer lineNo;
    @Column(name = "PID_ACCOUNT")
    private Long pidAccount;
    @Column(name = "DEBIT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal debit = BigDecimal.ZERO;
    @Column(name = "CREDIT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal credit = BigDecimal.ZERO;
    @Column(name = "DESCRIPTION", length = 300)
    private String description;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "PID_COST_CENTER")
    private Long pidCostCenter;
    @Column(name = "PARTY_TYPE", length = 20)
    private String partyType;
    @Column(name = "PARTY_ID")
    private Long partyId;
    @Column(name = "PARTY_NAME", length = 150)
    private String partyName;
    @Column(name = "JOURNAL_DATE")
    private LocalDate journalDate;
    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "STATUS", length = 1)
    @Builder.Default private String status = "Y";
}
