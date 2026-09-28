package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_rule_line — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_RULE_LINE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccRuleLine {
    @Id @Column(name = "PID_RULE_LINE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_rule_line")
    @SequenceGenerator(name = "seq_acc_rule_line", sequenceName = "SEQ_ACC_RULE_LINE", allocationSize = 1)
    private Long pidRuleLine;

    @Column(name = "PID_RULE")
    private Long pidRule;
    @Column(name = "LINE_NO")
    private Integer lineNo;
    @Column(name = "SIDE", length = 1)
    private String side;
    @Column(name = "ACCOUNT_ROLE", length = 40)
    private String accountRole;
    @Column(name = "AMOUNT_KEY", length = 40)
    private String amountKey;
    @Column(name = "PARTY_TYPE", length = 20)
    private String partyType;
    @Column(name = "DESCRIPTION", length = 200)
    private String description;
    @Column(name = "STATUS", length = 1)
    @Builder.Default private String status = "Y";
}
