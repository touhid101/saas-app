package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_ORGANIZATION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllOrganization extends BaseEntity {

    @Id @Column(name = "PID_ORG")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_org")
    @SequenceGenerator(name = "seq_org", sequenceName = "SEQ_ALL_ORGANIZATION", allocationSize = 1)
    private Long pidOrg;

    @Column(name = "ORG_NAME", length = 100, nullable = false)
    private String orgName;

    @Column(name = "ORG_SHORTNAME", length = 20)
    private String orgShortname;

    @Column(name = "EMAIL", length = 50)
    private String email;

    @Column(name = "PHONE", length = 20)
    private String phone;

    @Column(name = "COMPANY_ADDRESS", length = 200)
    private String companyAddress;
}
