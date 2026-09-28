package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "POS_PRODUCT_CATEGORY", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosProductCategory extends BaseEntity {

    @Id @Column(name = "PID_CATEGORY")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_cat")
    @SequenceGenerator(name = "seq_cat", sequenceName = "SEQ_PID_CATEGORY", allocationSize = 1)
    private Long pidCategory;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "CATEGORY_NAME", length = 50, nullable = false)
    private String categoryName;

    @Column(name = "FILE_NAME", length = 200)
    private String fileName;

    @Column(name = "FILE_MIME_TYPE", length = 100)
    private String fileMimeType;
}
