package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_REGION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllRegion extends BaseEntity {

    @Id @Column(name = "PID_REGION")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_region")
    @SequenceGenerator(name = "seq_region", sequenceName = "SEQ_ALL_REGION", allocationSize = 1)
    private Long pidRegion;

    @Column(name = "REGION_NAME", length = 100, nullable = false)
    private String regionName;
}
