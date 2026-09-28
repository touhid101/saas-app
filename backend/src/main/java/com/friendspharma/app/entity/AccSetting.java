package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_setting — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_SETTING", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccSetting extends BaseEntity {
    @Id @Column(name = "PID_SETTING")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_setting")
    @SequenceGenerator(name = "seq_acc_setting", sequenceName = "SEQ_ACC_SETTING", allocationSize = 1)
    private Long pidSetting;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "SETTING_KEY", length = 60)
    private String settingKey;
    @Column(name = "SETTING_VALUE", length = 200)
    private String settingValue;
}
