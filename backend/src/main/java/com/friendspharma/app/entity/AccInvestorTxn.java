package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_investor_txn — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_INVESTOR_TXN", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccInvestorTxn extends BaseEntity {
    @Id @Column(name = "PID_INVESTOR_TXN")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_investor_txn")
    @SequenceGenerator(name = "seq_acc_investor_txn", sequenceName = "SEQ_ACC_INVESTOR_TXN", allocationSize = 1)
    private Long pidInvestorTxn;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "PID_INVESTOR")
    private Long pidInvestor;
    @Column(name = "TXN_NO", length = 30)
    private String txnNo;
    @Column(name = "TXN_DATE")
    private LocalDate txnDate;
    @Column(name = "TXN_TYPE", length = 30)
    private String txnType;
    @Column(name = "AMOUNT", precision = 18, scale = 2)
    private BigDecimal amount;
    @Column(name = "DIRECTION")
    @Builder.Default private Integer direction = 1;
    @Column(name = "PAYMENT_MODE", length = 30)
    private String paymentMode;
    @Column(name = "REFERENCE", length = 60)
    private String reference;
    @Column(name = "DESCRIPTION", length = 300)
    private String description;
    @Column(name = "REASON", length = 500)
    private String reason;
    @Column(name = "PID_DISTRIBUTION")
    private Long pidDistribution;
    @Column(name = "PID_ALLOCATION")
    private Long pidAllocation;
    @Column(name = "TXN_STATUS", length = 10)
    @Builder.Default private String txnStatus = "DRAFT";
    @Column(name = "APPROVED_BY", length = 50)
    private String approvedBy;
    @Column(name = "APPROVED_AT")
    private LocalDateTime approvedAt;
    @Column(name = "POSTED_BY", length = 50)
    private String postedBy;
    @Column(name = "POSTED_AT")
    private LocalDateTime postedAt;
    @Column(name = "CANCELLED_BY", length = 50)
    private String cancelledBy;
    @Column(name = "CANCELLED_AT")
    private LocalDateTime cancelledAt;
    @Column(name = "CANCEL_REASON", length = 500)
    private String cancelReason;
    @Column(name = "PID_EVENT")
    private Long pidEvent;
    @Column(name = "PID_JOURNAL")
    private Long pidJournal;
}
