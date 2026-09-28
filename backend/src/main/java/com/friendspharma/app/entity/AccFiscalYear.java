package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_fiscal_year — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_FISCAL_YEAR", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccFiscalYear extends BaseEntity {
    @Id @Column(name = "PID_FISCAL_YEAR")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_fiscal_year")
    @SequenceGenerator(name = "seq_acc_fiscal_year", sequenceName = "SEQ_ACC_FISCAL_YEAR", allocationSize = 1)
    private Long pidFiscalYear;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "YEAR_CODE", length = 20)
    private String yearCode;
    @Column(name = "START_DATE")
    private LocalDate startDate;
    @Column(name = "END_DATE")
    private LocalDate endDate;
    @Column(name = "YEAR_STATUS", length = 10)
    @Builder.Default private String yearStatus = "OPEN";
    @Column(name = "CLOSED_BY", length = 50)
    private String closedBy;
    @Column(name = "CLOSED_AT")
    private LocalDateTime closedAt;
    @Column(name = "PID_CLOSING_JOURNAL")
    private Long pidClosingJournal;
}
