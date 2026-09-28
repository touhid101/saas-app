package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "POS_PRODUCT_SUB_CATEGORY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosProductSubCategory extends BaseEntity {

    @Id @Column(name = "PID_SUB_CATEGORY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_sub_cat")
    @SequenceGenerator(name = "seq_sub_cat", sequenceName = "SEQ_PID_SUB_CATEGORY", allocationSize = 1)
    private Long pidSubCategory;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    /** The pharmacy this row belongs to. Added with V30; every row has one. */
    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_CATEGORY")
    private Long pidCategory;

    @Column(name = "SUB_CATEGORY_NAME", length = 50, nullable = false)
    private String subCategoryName;
}
