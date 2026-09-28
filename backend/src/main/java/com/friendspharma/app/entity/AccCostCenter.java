package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_cost_center — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_COST_CENTER", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccCostCenter extends BaseEntity {
    @Id @Column(name = "PID_COST_CENTER")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_cost_center")
    @SequenceGenerator(name = "seq_acc_cost_center", sequenceName = "SEQ_ACC_COST_CENTER", allocationSize = 1)
    private Long pidCostCenter;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "CC_CODE", length = 20)
    private String ccCode;
    @Column(name = "CC_NAME", length = 120)
    private String ccName;
    @Column(name = "DESCRIPTION", length = 500)
    private String description;
    @Column(name = "IS_ACTIVE")
    @Builder.Default private Integer isActive = 1;
}
