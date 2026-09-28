package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "POS_AREA_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosAreaInfo extends BaseEntity {

    @Id @Column(name = "PID_AREA")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_area")
    @SequenceGenerator(name = "seq_area", sequenceName = "SEQ_PID_AREA", allocationSize = 1)
    private Long pidArea;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "PID_THANA")
    private Long pidThana;

    @Column(name = "AREA_NAME", length = 50)
    private String areaName;

    @Column(name = "PID_EMPLOYEE")
    private Long pidEmployee;
}
