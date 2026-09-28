package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "HR_DESIGNATION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrDesignation extends BaseEntity {

    @Id @Column(name = "PID_DESIGNATION")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_desig")
    @SequenceGenerator(name = "seq_desig", sequenceName = "SEQ_HR_DESIGNATION", allocationSize = 1)
    private Long pidDesignation;

    @Column(name = "PID_ORG")
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "DESIGNATION_NAME", length = 200, nullable = false)
    private String designationName;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;
}
