package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_profit_allocation — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_PROFIT_ALLOCATION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccProfitAllocation extends BaseEntity {
    @Id @Column(name = "PID_ALLOCATION")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_profit_allocation")
    @SequenceGenerator(name = "seq_acc_profit_allocation", sequenceName = "SEQ_ACC_PROFIT_ALLOCATION", allocationSize = 1)
    private Long pidAllocation;

    @Column(name = "PID_DISTRIBUTION")
    private Long pidDistribution;
    @Column(name = "PID_INVESTOR")
    private Long pidInvestor;
    @Column(name = "OPENING_CAPITAL", precision = 18, scale = 2)
    @Builder.Default private BigDecimal openingCapital = BigDecimal.ZERO;
    @Column(name = "ADDITIONS", precision = 18, scale = 2)
    @Builder.Default private BigDecimal additions = BigDecimal.ZERO;
    @Column(name = "WITHDRAWALS", precision = 18, scale = 2)
    @Builder.Default private BigDecimal withdrawals = BigDecimal.ZERO;
    @Column(name = "ELIGIBLE_CAPITAL", precision = 18, scale = 2)
    @Builder.Default private BigDecimal eligibleCapital = BigDecimal.ZERO;
    @Column(name = "SHARE_PCT", precision = 9, scale = 4)
    @Builder.Default private BigDecimal sharePct = BigDecimal.ZERO;
    @Column(name = "ALLOCATED_AMOUNT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal allocatedAmount = BigDecimal.ZERO;
    @Column(name = "PAID_AMOUNT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal paidAmount = BigDecimal.ZERO;
    @Column(name = "ALLOC_STATUS", length = 10)
    @Builder.Default private String allocStatus = "DRAFT";
    @Column(name = "PID_INVESTOR_TXN")
    private Long pidInvestorTxn;
}
