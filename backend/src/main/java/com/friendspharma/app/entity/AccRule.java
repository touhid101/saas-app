package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_rule — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_RULE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccRule extends BaseEntity {
    @Id @Column(name = "PID_RULE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_rule")
    @SequenceGenerator(name = "seq_acc_rule", sequenceName = "SEQ_ACC_RULE", allocationSize = 1)
    private Long pidRule;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "EVENT_TYPE", length = 40)
    private String eventType;
    @Column(name = "RULE_NAME", length = 120)
    private String ruleName;
    @Column(name = "DESCRIPTION", length = 500)
    private String description;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
}
