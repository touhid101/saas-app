package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_business_day_audit — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_BUSINESS_DAY_AUDIT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccBusinessDayAudit {
    @Id @Column(name = "PID_DAY_AUDIT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_day_audit")
    @SequenceGenerator(name = "seq_acc_day_audit", sequenceName = "SEQ_ACC_DAY_AUDIT", allocationSize = 1)
    private Long pidDayAudit;

    @Column(name = "PID_BUSINESS_DAY")
    private Long pidBusinessDay;
    @Column(name = "ACTION", length = 20)
    private String action;
    @Column(name = "DETAIL", length = 1000)
    private String detail;
    @Column(name = "ACTED_BY", length = 50)
    private String actedBy;
    @Column(name = "ACTED_AT")
    private LocalDateTime actedAt;
}
