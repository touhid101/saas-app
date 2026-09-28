package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_denomination — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_DENOMINATION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccDenomination extends BaseEntity {
    @Id @Column(name = "PID_DENOMINATION")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_denomination")
    @SequenceGenerator(name = "seq_acc_denomination", sequenceName = "SEQ_ACC_DENOMINATION", allocationSize = 1)
    private Long pidDenomination;

    @Column(name = "CURRENCY", length = 3)
    private String currency;
    @Column(name = "DENOM_VALUE", precision = 18, scale = 2)
    private BigDecimal denomValue;
    @Column(name = "DENOM_KIND", length = 10)
    @Builder.Default private String denomKind = "NOTE";
    @Column(name = "SORT_ORDER")
    @Builder.Default private Integer sortOrder = 100;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
}
