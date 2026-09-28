package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** POS_COMPANY_INFO — product manufacturer company (distinct from ALL_COMPANY which is the org company) */
@Entity @Table(name = "POS_COMPANY_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosCompanyInfo extends BaseEntity {

    @Id @Column(name = "PID_COMPANY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_pos_co")
    @SequenceGenerator(name = "seq_pos_co", sequenceName = "SEQ_PID_COMPANY", allocationSize = 1)
    private Long pidCompany;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "COMPANY_NAME", length = 100)
    private String companyName;

    @Column(name = "RESPONSIBLE_PERSON", length = 50)
    private String responsiblePerson;

    @Column(name = "RESP_PHONE_NO", length = 20)
    private String respPhoneNo;

    @Column(name = "MOBILE_NO", length = 20)
    private String mobileNo;

    @Column(name = "EMAIL_ADDRESS", length = 50)
    private String emailAddress;

    @Column(name = "ADDRESS", length = 200)
    private String address;

    @Column(name = "CITY", length = 50)
    private String city;
}
