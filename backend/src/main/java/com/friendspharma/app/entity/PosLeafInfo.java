package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "POS_LEAF_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosLeafInfo extends BaseEntity {

    @Id @Column(name = "PID_LEAF")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_leaf")
    @SequenceGenerator(name = "seq_leaf", sequenceName = "SEQ_PID_LEAF", allocationSize = 1)
    private Long pidLeaf;

    @Column(name = "PID_ORG")
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "LEAF_TYPE", length = 50)
    private String leafType;

    /** Number of PCS per leaf/strip (pcsPerLeaf in React) */
    @Column(name = "NO_OF_PCS")
    private Integer noOfPcs;
}
