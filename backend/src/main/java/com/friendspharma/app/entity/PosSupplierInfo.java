package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity @Table(name = "POS_SUPPLIER_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosSupplierInfo extends BaseEntity {

    @Id @Column(name = "PID_SUPPLIER")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_supp")
    @SequenceGenerator(name = "seq_supp", sequenceName = "SEQ_PID_SUPPLIER", allocationSize = 1)
    private Long pidSupplier;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    /** The company the pharmacy belongs to. Follows the branch. */
    @Column(name = "PID_COMPANY", nullable = false)
    private Long pidCompany;

    @Column(name = "PID_AREA")
    private Long pidArea;

    /** FK to POS_COMPANY_INFO (manufacturer company) */
    @Column(name = "POS_PID_COMPANY")
    private Long posPidCompany;

    @Column(name = "SUPPLIER_NAME", length = 100)
    private String supplierName;

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

    @Column(name = "RESPONSIBLE_PERSON", length = 50)
    private String responsiblePerson;

    @Column(name = "OPENING_BAL", precision = 12, scale = 2)
    private BigDecimal openingBal;

    @Column(name = "CREDIT_LIMIT", precision = 12, scale = 2)
    private BigDecimal creditLimit;
}
