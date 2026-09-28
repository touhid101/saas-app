package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_cash_count — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_CASH_COUNT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccCashCount extends BaseEntity {
    @Id @Column(name = "PID_CASH_COUNT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_cash_count")
    @SequenceGenerator(name = "seq_acc_cash_count", sequenceName = "SEQ_ACC_CASH_COUNT", allocationSize = 1)
    private Long pidCashCount;

    @Column(name = "PID_BUSINESS_DAY")
    private Long pidBusinessDay;
    @Column(name = "DENOM_VALUE", precision = 18, scale = 2)
    private BigDecimal denomValue;
    @Column(name = "QUANTITY")
    @Builder.Default private Integer quantity = 0;
    @Column(name = "AMOUNT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal amount = BigDecimal.ZERO;
}
