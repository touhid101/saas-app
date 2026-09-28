package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "POS_BOX_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosBoxInfo extends BaseEntity {

    @Id @Column(name = "PID_BOX")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_box")
    @SequenceGenerator(name = "seq_box", sequenceName = "SEQ_PID_BOX", allocationSize = 1)
    private Long pidBox;

    @Column(name = "PID_LEAF")
    private Long pidLeaf;

    @Column(name = "PID_ORG")
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    /** Display title for the box e.g. "Box 10×10 (100 pcs)" */
    @Column(name = "BOX_SIZE_TITLE", length = 50)
    private String boxSizeTitle;

    /** Number of leaf/strips per box (leavesPerBox in React) */
    @Column(name = "NO_OF_LEAF")
    private Integer noOfLeaf;
}
