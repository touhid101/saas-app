package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_DEPARTMENTS", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllDepartments extends BaseEntity {

    @Id @Column(name = "PID_DEPARTMENT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_dept")
    @SequenceGenerator(name = "seq_dept", sequenceName = "SEQ_ALL_DEPARTMENTS", allocationSize = 1)
    private Long pidDepartment;

    @Column(name = "PID_ORG")
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "DEPARTMENT_NAME", length = 200, nullable = false)
    private String departmentName;
}
