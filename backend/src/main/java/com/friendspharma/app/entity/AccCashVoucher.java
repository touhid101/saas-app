package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_cash_voucher — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_CASH_VOUCHER", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccCashVoucher extends BaseEntity {
    @Id @Column(name = "PID_CASH_VOUCHER")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_cash_voucher")
    @SequenceGenerator(name = "seq_acc_cash_voucher", sequenceName = "SEQ_ACC_CASH_VOUCHER", allocationSize = 1)
    private Long pidCashVoucher;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "VOUCHER_NO", length = 30)
    private String voucherNo;
    @Column(name = "VOUCHER_DATE")
    private LocalDate voucherDate;
    @Column(name = "VOUCHER_KIND", length = 20)
    private String voucherKind;
    @Column(name = "PID_CASH_ACCOUNT")
    private Long pidCashAccount;
    @Column(name = "PID_COUNTER_ACCOUNT")
    private Long pidCounterAccount;
    @Column(name = "AMOUNT", precision = 18, scale = 2)
    private BigDecimal amount;
    @Column(name = "PARTY_TYPE", length = 20)
    private String partyType;
    @Column(name = "PARTY_ID")
    private Long partyId;
    @Column(name = "PARTY_NAME", length = 150)
    private String partyName;
    @Column(name = "REFERENCE", length = 60)
    private String reference;
    @Column(name = "DESCRIPTION", length = 300)
    private String description;
    @Column(name = "REASON", length = 500)
    private String reason;
    @Column(name = "PID_COST_CENTER")
    private Long pidCostCenter;
    @Column(name = "VOUCHER_STATUS", length = 10)
    @Builder.Default private String voucherStatus = "DRAFT";
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
