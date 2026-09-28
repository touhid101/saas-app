package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_period — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_PERIOD", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccPeriod extends BaseEntity {
    @Id @Column(name = "PID_PERIOD")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_period")
    @SequenceGenerator(name = "seq_acc_period", sequenceName = "SEQ_ACC_PERIOD", allocationSize = 1)
    private Long pidPeriod;

    @Column(name = "PID_FISCAL_YEAR")
    private Long pidFiscalYear;
    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PERIOD_NO")
    private Integer periodNo;
    @Column(name = "PERIOD_NAME", length = 30)
    private String periodName;
    @Column(name = "START_DATE")
    private LocalDate startDate;
    @Column(name = "END_DATE")
    private LocalDate endDate;
    @Column(name = "PERIOD_STATUS", length = 10)
    @Builder.Default private String periodStatus = "OPEN";
    @Column(name = "CLOSED_BY", length = 50)
    private String closedBy;
    @Column(name = "CLOSED_AT")
    private LocalDateTime closedAt;
    @Column(name = "LOCKED_BY", length = 50)
    private String lockedBy;
    @Column(name = "LOCKED_AT")
    private LocalDateTime lockedAt;
}
