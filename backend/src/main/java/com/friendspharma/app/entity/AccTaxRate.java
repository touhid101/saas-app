package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_tax_rate — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_TAX_RATE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccTaxRate extends BaseEntity {
    @Id @Column(name = "PID_TAX_RATE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_tax_rate")
    @SequenceGenerator(name = "seq_acc_tax_rate", sequenceName = "SEQ_ACC_TAX_RATE", allocationSize = 1)
    private Long pidTaxRate;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "TAX_CODE", length = 20)
    private String taxCode;
    @Column(name = "TAX_NAME", length = 120)
    private String taxName;
    @Column(name = "TAX_TYPE", length = 20)
    private String taxType;
    @Column(name = "RATE_PCT", precision = 9, scale = 4)
    private BigDecimal ratePct;
    @Column(name = "EFFECTIVE_FROM")
    private LocalDate effectiveFrom;
    @Column(name = "EFFECTIVE_TO")
    private LocalDate effectiveTo;
    @Column(name = "PID_ACCOUNT")
    private Long pidAccount;
    @Column(name = "IS_INCLUSIVE")
    @Builder.Default private Integer isInclusive = 0;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
}
