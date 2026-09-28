package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_COMPANY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllCompany extends BaseEntity {

    @Id @Column(name = "PID_COMPANY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_all_co")
    @SequenceGenerator(name = "seq_all_co", sequenceName = "SEQ_ALL_COMPANY", allocationSize = 1)
    private Long pidCompany;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "COMPANY_NAME", length = 100, nullable = false)
    private String companyName;

    @Column(name = "COMPANY_SHORT_NAME", length = 20)
    private String companyShortName;

    @Column(name = "EMAIL", length = 50)
    private String email;

    @Column(name = "PHONE", length = 20)
    private String phone;

    @Column(name = "COMPANY_ADDRESS", length = 200)
    private String companyAddress;
}
