package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "ALL_DISTRICT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllDistrict {

    @Id @Column(name = "DISTRICT_ID")
    private Long districtId;

    @Column(name = "DIVISION_ID", nullable = false)
    private Long divisionId;

    @Column(name = "DISTRICT_NAME", length = 100, nullable = false)
    private String districtName;

    @Column(name = "DISTRICT_BN", length = 100)
    private String districtBn;
}
