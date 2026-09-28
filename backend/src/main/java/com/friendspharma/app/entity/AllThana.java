package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_THANA", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllThana {

    @Id @Column(name = "THANA_ID")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_thana")
    @SequenceGenerator(name = "seq_thana", sequenceName = "SEQ_ALL_THANA", allocationSize = 1)
    private Long thanaId;

    @Column(name = "DISTRICT_ID", nullable = false)
    private Long districtId;

    @Column(name = "THANA_NAME", length = 150, nullable = false)
    private String thanaName;

    @Column(name = "THANA_BN", length = 150)
    private String thanaBn;
}
