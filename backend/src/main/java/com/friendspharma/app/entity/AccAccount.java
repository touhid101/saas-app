package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_account — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_ACCOUNT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccAccount extends BaseEntity {
    @Id @Column(name = "PID_ACCOUNT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_account")
    @SequenceGenerator(name = "seq_acc_account", sequenceName = "SEQ_ACC_ACCOUNT", allocationSize = 1)
    private Long pidAccount;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "ACCOUNT_CODE", length = 20)
    private String accountCode;
    @Column(name = "ACCOUNT_NAME", length = 150)
    private String accountName;
    @Column(name = "ACCOUNT_TYPE", length = 10)
    private String accountType;
    @Column(name = "PID_PARENT")
    private Long pidParent;
    @Column(name = "IS_GROUP")
    @Builder.Default private Integer isGroup = 0;
    @Column(name = "NORMAL_SIDE", length = 1)
    private String normalSide;
    @Column(name = "ROLE_KEY", length = 40)
    private String roleKey;
    @Column(name = "ALLOW_MANUAL")
    @Builder.Default private Integer allowManual = 1;
    @Column(name = "IS_SYSTEM")
    @Builder.Default private Integer isSystem = 0;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
    @Column(name = "CURRENCY", length = 3)
    @Builder.Default private String currency = "BDT";
    @Column(name = "DESCRIPTION", length = 500)
    private String description;
    @Column(name = "SORT_ORDER")
    @Builder.Default private Integer sortOrder = 100;
}
