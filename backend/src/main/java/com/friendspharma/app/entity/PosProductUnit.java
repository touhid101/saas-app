package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "POS_PRODUCT_UNIT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosProductUnit extends BaseEntity {

    @Id @Column(name = "PID_UNIT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_unit")
    @SequenceGenerator(name = "seq_unit", sequenceName = "SEQ_PID_UNIT", allocationSize = 1)
    private Long pidUnit;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "UNIT_NAME", length = 20, nullable = false)
    private String unitName;
}
