package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "POS_GENERIC_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosGenericInfo extends BaseEntity {

    @Id @Column(name = "PID_GENERIC")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_generic")
    @SequenceGenerator(name = "seq_generic", sequenceName = "SEQ_POS_GENERIC_INFO", allocationSize = 1)
    private Long pidGeneric;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    /** The pharmacy this row belongs to. Added with V30; every row has one. */
    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

 /** FK to POS_COMPANY_INFO (manufacturer company), as on PosSupplierInfo. */
       @Column(name = "POS_PID_COMPANY")
       private Long posPidCompany;
   
       /** The pharmacy's own company. Tenancy, not the manufacturer. */
       @Column(name = "PID_COMPANY", nullable = false)
       private Long pidCompany;

    @Column(name = "GENERIC_NAME", length = 200, nullable = false)
    private String genericName;

    /** [NEW] Description from React GENERICS.description */
    @Column(name = "DESCRIPTION", length = 500)
    private String description;
}
