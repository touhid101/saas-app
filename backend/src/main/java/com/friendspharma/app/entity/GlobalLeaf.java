package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A leaf/strip in the catalogue — a named strip and how many pieces it holds.
 *
 * <p>The same thing a pharmacy's own Leaf/Strip master describes, so the person
 * curating the catalogue thinks in the shape the rest of the application uses.
 *
 * <p>Part of the catalogue, not of any pharmacy: it carries no branch. Importing a
 * medicine creates the receiving pharmacy's own leaf row from this one.
 */
@Entity
@Table(name = "GLOBAL_LEAF")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlobalLeaf extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PID_GLOBAL_LEAF")
    private Long pidGlobalLeaf;

    @Column(name = "LEAF_TYPE", nullable = false, length = 60)
    private String leafType;

    @Column(name = "NO_OF_PCS", nullable = false)
    private Integer noOfPcs;
}
