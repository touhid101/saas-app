package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_profit_policy — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_PROFIT_POLICY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccProfitPolicy extends BaseEntity {
    @Id @Column(name = "PID_PROFIT_POLICY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_profit_policy")
    @SequenceGenerator(name = "seq_acc_profit_policy", sequenceName = "SEQ_ACC_PROFIT_POLICY", allocationSize = 1)
    private Long pidProfitPolicy;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "POLICY_NAME", length = 120)
    private String policyName;
    @Column(name = "SHARE_METHOD", length = 40)
    private String shareMethod;
    @Column(name = "INVESTOR_POOL_PCT", precision = 9, scale = 4)
    @Builder.Default private BigDecimal investorPoolPct = new BigDecimal("100");
    @Column(name = "RETAINED_PCT", precision = 9, scale = 4)
    @Builder.Default private BigDecimal retainedPct = BigDecimal.ZERO;
    @Column(name = "EFFECTIVE_FROM")
    private LocalDate effectiveFrom;
    @Column(name = "EFFECTIVE_TO")
    private LocalDate effectiveTo;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
    @Column(name = "NOTES", length = 500)
    private String notes;
}
