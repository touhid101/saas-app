package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_DIVISION", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllDivision {

    @Id @Column(name = "DIVISION_ID")
    private Long divisionId;

    @Column(name = "DIVISION_NAME", length = 100, nullable = false)
    private String divisionName;

    @Column(name = "DIVISION_BN", length = 100)
    private String divisionBn;

    @Column(name = "PID_REGION")
    private Long pidRegion;
}
