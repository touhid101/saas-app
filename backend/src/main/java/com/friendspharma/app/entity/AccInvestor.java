package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** acc_investor — see V90..V92 and docs/accounts/database.md. Generated from the migration spec; keep the two in step. */
@Entity @Table(name = "ACC_INVESTOR", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccInvestor extends BaseEntity {
    @Id @Column(name = "PID_INVESTOR")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acc_investor")
    @SequenceGenerator(name = "seq_acc_investor", sequenceName = "SEQ_ACC_INVESTOR", allocationSize = 1)
    private Long pidInvestor;

    @Column(name = "PID_ORG")
    private Long pidOrg;
    @Column(name = "PID_BRANCH")
    private Long pidBranch;
    @Column(name = "INVESTOR_CODE", length = 20)
    private String investorCode;
    @Column(name = "INVESTOR_NAME", length = 150)
    private String investorName;
    @Column(name = "CONTACT_PHONE", length = 30)
    private String contactPhone;
    @Column(name = "CONTACT_EMAIL", length = 120)
    private String contactEmail;
    @Column(name = "ADDRESS", length = 300)
    private String address;
    @Column(name = "ID_REFERENCE", length = 60)
    private String idReference;
    @Column(name = "INVESTMENT_TYPE", length = 20)
    @Builder.Default private String investmentType = "EQUITY";
    @Column(name = "START_DATE")
    private LocalDate startDate;
    @Column(name = "END_DATE")
    private LocalDate endDate;
    @Column(name = "SHARE_PCT", precision = 9, scale = 4)
    private BigDecimal sharePct;
    @Column(name = "BANK_NAME", length = 120)
    private String bankName;
    @Column(name = "BANK_ACCOUNT_NO", length = 60)
    private String bankAccountNo;
    @Column(name = "MOBILE_WALLET", length = 30)
    private String mobileWallet;
    @Column(name = "REMARKS", length = 500)
    private String remarks;
    @Column(name = "INVESTOR_STATUS", length = 10)
    @Builder.Default private String investorStatus = "ACTIVE";
}
