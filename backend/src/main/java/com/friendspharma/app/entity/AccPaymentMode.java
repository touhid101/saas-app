package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_payment_mode — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_PAYMENT_MODE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccPaymentMode extends BaseEntity {
    @Id @Column(name = "PID_PAYMENT_MODE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_payment_mode")
    @SequenceGenerator(name = "seq_acc_payment_mode", sequenceName = "SEQ_ACC_PAYMENT_MODE", allocationSize = 1)
    private Long pidPaymentMode;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "MODE_KEY", length = 30)
    private String modeKey;
    @Column(name = "MODE_LABEL", length = 60)
    private String modeLabel;
    @Column(name = "ACCOUNT_ROLE", length = 40)
    private String accountRole;
    @Column(name = "PID_BANK_ACCOUNT")
    private Long pidBankAccount;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
}
