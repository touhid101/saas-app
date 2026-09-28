package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_profit_policy_line — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_PROFIT_POLICY_LINE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccProfitPolicyLine {
    @Id @Column(name = "PID_POLICY_LINE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_profit_policy_line")
    @SequenceGenerator(name = "seq_acc_profit_policy_line", sequenceName = "SEQ_ACC_PROFIT_POLICY_LINE", allocationSize = 1)
    private Long pidPolicyLine;

    @Column(name = "PID_PROFIT_POLICY")
    private Long pidProfitPolicy;
    @Column(name = "PID_INVESTOR")
    private Long pidInvestor;
    @Column(name = "SHARE_PCT", precision = 9, scale = 4)
    private BigDecimal sharePct;
    @Column(name = "STATUS", length = 1)
    @Builder.Default private String status = "Y";
}
