package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

/** Which pharmacies a branch-scoped points rule covers — {@code PHARMA.ECOM_POINT_RULE_BRANCH} (V111). */
@Entity
@Table(name = "ECOM_POINT_RULE_BRANCH", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomPointRuleBranch extends com.friendspharma.app.entity.base.BaseEntity {

    @Id
    @Column(name = "PID_RULE_BRANCH")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_point_rule_branch")
    @SequenceGenerator(name = "seq_ecom_point_rule_branch", sequenceName = "SEQ_ECOM_POINT_RULE_BRANCH", allocationSize = 1)
    private Long pidRuleBranch;

    @Column(name = "PID_POINT_RULE", nullable = false) private Long pidPointRule;
    @Column(name = "PID_BRANCH", nullable = false)     private Long pidBranch;
}
