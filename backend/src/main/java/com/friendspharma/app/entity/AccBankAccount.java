package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_bank_account — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_BANK_ACCOUNT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccBankAccount extends BaseEntity {
    @Id @Column(name = "PID_BANK_ACCOUNT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_bank_account")
    @SequenceGenerator(name = "seq_acc_bank_account", sequenceName = "SEQ_ACC_BANK_ACCOUNT", allocationSize = 1)
    private Long pidBankAccount;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "PID_ACCOUNT")
    private Long pidAccount;
    @Column(name = "BANK_NAME", length = 120)
    private String bankName;
    @Column(name = "ACCOUNT_TITLE", length = 150)
    private String accountTitle;
    @Column(name = "ACCOUNT_NO", length = 60)
    private String accountNo;
    @Column(name = "BRANCH_NAME", length = 120)
    private String branchName;
    @Column(name = "CURRENCY", length = 3)
    @Builder.Default private String currency = "BDT";
    @Column(name = "IS_DEFAULT")
    @Builder.Default private Integer isDefault = 0;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
}
