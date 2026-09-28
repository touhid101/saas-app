package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity @Table(name = "POS_CUSTOMER_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosCustomerInfo extends BaseEntity {

    @Id @Column(name = "PID_CUSTOMER")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_cust")
    @SequenceGenerator(name = "seq_cust", sequenceName = "SEQ_ACL_CUSTOMER_ONLINE", allocationSize = 1)
    private Long pidCustomer;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    /** The company the pharmacy belongs to. Follows the branch. */
    @Column(name = "PID_COMPANY", nullable = false)
    private Long pidCompany;

    @Column(name = "PID_AREA")
    private Long pidArea;

    @Column(name = "CUSTOMER_CODE", length = 15)
    private String customerCode;

    @Column(name = "CUSTOMER_NAME", length = 100, nullable = false)
    private String customerName;

    /**
     * Optional, and unique only within a pharmacy.
     *
     * <p>The global {@code unique} here was wrong for a multi-tenant table: two
     * pharmacies may each know the same person, and each keeps its own record of
     * them. The real constraint is the partial index {@code uq_customer_branch_mobile}
     * on (pid_branch, mobile_no), which also lets any number of rows carry no
     * number at all.
     */
    @Column(name = "MOBILE_NO", length = 20)
    private String mobileNo;

    @Column(name = "RESP_PHONE_NO", length = 20)
    private String respPhoneNo;

    @Column(name = "EMAIL_ADDRESS", length = 50)
    private String emailAddress;

    @Column(name = "ADDRESS", length = 200)
    private String address;

    @Column(name = "CITY", length = 50)
    private String city;

    @Column(name = "OWNER_NAME", length = 100)
    private String ownerName;

    @Column(name = "OPENING_BAL", precision = 12, scale = 2)
    private BigDecimal openingBal;

    /** [NEW] Customer type: Hospital/Pharmacy/Clinic/Walk-in */
    @Column(name = "CUSTOMER_TYPE", length = 20)
    private String customerType;

    /** [NEW] Credit limit from React mockData */
    @Column(name = "CREDIT_LIMIT", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /**
     * [V102] Y when this row is the shadow of a global e-commerce customer
     * (see {@link EcomCustomerBranchLink}). Its type is then a copy of the
     * global tier and is changed from the E-Commerce Customers console, not on
     * the Customers screen. Null for every row staff made themselves.
     */
    @Column(name = "ECOM_MANAGED", length = 1)
    private String ecomManaged;

    public boolean isEcomManaged() { return "Y".equals(ecomManaged); }
}
