package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_profit_distribution — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_PROFIT_DISTRIBUTION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccProfitDistribution extends BaseEntity {
    @Id @Column(name = "PID_DISTRIBUTION")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_profit_distribution")
    @SequenceGenerator(name = "seq_acc_profit_distribution", sequenceName = "SEQ_ACC_PROFIT_DISTRIBUTION", allocationSize = 1)
    private Long pidDistribution;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "DISTRIBUTION_NO", length = 30)
    private String distributionNo;
    @Column(name = "PERIOD_FROM")
    private LocalDate periodFrom;
    @Column(name = "PERIOD_TO")
    private LocalDate periodTo;
    @Column(name = "PID_PERIOD_FROM")
    private Long pidPeriodFrom;
    @Column(name = "PID_PERIOD_TO")
    private Long pidPeriodTo;
    @Column(name = "PID_PROFIT_POLICY")
    private Long pidProfitPolicy;
    @Column(name = "SHARE_METHOD", length = 40)
    private String shareMethod;
    @Column(name = "REVENUE", precision = 18, scale = 2)
    @Builder.Default private BigDecimal revenue = BigDecimal.ZERO;
    @Column(name = "COST_OF_SALES", precision = 18, scale = 2)
    @Builder.Default private BigDecimal costOfSales = BigDecimal.ZERO;
    @Column(name = "OPERATING_EXPENSES", precision = 18, scale = 2)
    @Builder.Default private BigDecimal operatingExpenses = BigDecimal.ZERO;
    @Column(name = "OTHER_INCOME", precision = 18, scale = 2)
    @Builder.Default private BigDecimal otherIncome = BigDecimal.ZERO;
    @Column(name = "OTHER_EXPENSE", precision = 18, scale = 2)
    @Builder.Default private BigDecimal otherExpense = BigDecimal.ZERO;
    @Column(name = "ACCOUNTING_PROFIT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal accountingProfit = BigDecimal.ZERO;
    @Column(name = "RETAINED_PCT", precision = 9, scale = 4)
    @Builder.Default private BigDecimal retainedPct = BigDecimal.ZERO;
    @Column(name = "RETAINED_AMOUNT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal retainedAmount = BigDecimal.ZERO;
    @Column(name = "ADJUSTMENT_AMOUNT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal adjustmentAmount = BigDecimal.ZERO;
    @Column(name = "ADJUSTMENT_REASON", length = 500)
    private String adjustmentReason;
    @Column(name = "DISTRIBUTABLE_PROFIT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal distributableProfit = BigDecimal.ZERO;
    @Column(name = "INVESTOR_POOL_PCT", precision = 9, scale = 4)
    @Builder.Default private BigDecimal investorPoolPct = new BigDecimal("100");
    @Column(name = "ALLOCATED_TOTAL", precision = 18, scale = 2)
    @Builder.Default private BigDecimal allocatedTotal = BigDecimal.ZERO;
    @Column(name = "PAID_TOTAL", precision = 18, scale = 2)
    @Builder.Default private BigDecimal paidTotal = BigDecimal.ZERO;
    @Column(name = "DIST_STATUS", length = 12)
    @Builder.Default private String distStatus = "DRAFT";
    @Column(name = "SNAPSHOT", columnDefinition = "TEXT")
    private String snapshot;
    @Column(name = "CALCULATED_BY", length = 50)
    private String calculatedBy;
    @Column(name = "CALCULATED_AT")
    private LocalDateTime calculatedAt;
    @Column(name = "REVIEWED_BY", length = 50)
    private String reviewedBy;
    @Column(name = "REVIEWED_AT")
    private LocalDateTime reviewedAt;
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
    @Column(name = "NOTES", length = 1000)
    private String notes;
}
