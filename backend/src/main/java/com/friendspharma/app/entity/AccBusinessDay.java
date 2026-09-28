package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_business_day — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_BUSINESS_DAY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccBusinessDay extends BaseEntity {
    @Id @Column(name = "PID_BUSINESS_DAY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_business_day")
    @SequenceGenerator(name = "seq_acc_business_day", sequenceName = "SEQ_ACC_BUSINESS_DAY", allocationSize = 1)
    private Long pidBusinessDay;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "BUSINESS_DATE")
    private LocalDate businessDate;
    @Column(name = "PID_CASH_ACCOUNT")
    private Long pidCashAccount;
    @Column(name = "DAY_STATUS", length = 12)
    @Builder.Default private String dayStatus = "OPEN";
    @Column(name = "PID_PREVIOUS_DAY")
    private Long pidPreviousDay;
    @Column(name = "OPENING_CASH", precision = 18, scale = 2)
    @Builder.Default private BigDecimal openingCash = BigDecimal.ZERO;
    @Column(name = "CASH_IN", precision = 18, scale = 2)
    @Builder.Default private BigDecimal cashIn = BigDecimal.ZERO;
    @Column(name = "CASH_OUT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal cashOut = BigDecimal.ZERO;
    @Column(name = "EXPECTED_CLOSING", precision = 18, scale = 2)
    private BigDecimal expectedClosing;
    @Column(name = "PHYSICAL_CASH", precision = 18, scale = 2)
    private BigDecimal physicalCash;
    @Column(name = "DIFFERENCE", precision = 18, scale = 2)
    private BigDecimal difference;
    @Column(name = "DIFFERENCE_KIND", length = 10)
    private String differenceKind;
    @Column(name = "DIFFERENCE_REASON", length = 500)
    private String differenceReason;
    @Column(name = "VARIANCE_POSTED")
    @Builder.Default private Integer variancePosted = 0;
    @Column(name = "PID_VARIANCE_JOURNAL")
    private Long pidVarianceJournal;
    @Column(name = "OPENED_BY", length = 50)
    private String openedBy;
    @Column(name = "OPENED_AT")
    private LocalDateTime openedAt;
    @Column(name = "SUBMITTED_BY", length = 50)
    private String submittedBy;
    @Column(name = "SUBMITTED_AT")
    private LocalDateTime submittedAt;
    @Column(name = "REVIEWED_BY", length = 50)
    private String reviewedBy;
    @Column(name = "REVIEWED_AT")
    private LocalDateTime reviewedAt;
    @Column(name = "CLOSED_BY", length = 50)
    private String closedBy;
    @Column(name = "CLOSED_AT")
    private LocalDateTime closedAt;
    @Column(name = "REOPENED_BY", length = 50)
    private String reopenedBy;
    @Column(name = "REOPENED_AT")
    private LocalDateTime reopenedAt;
    @Column(name = "REOPEN_REASON", length = 500)
    private String reopenReason;
    @Column(name = "REOPEN_COUNT")
    @Builder.Default private Integer reopenCount = 0;
    @Column(name = "NOTES", length = 1000)
    private String notes;
}
