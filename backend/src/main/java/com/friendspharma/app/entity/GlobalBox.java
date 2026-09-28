package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A box in the catalogue — a named box, the strip it is built from, and how many
 * of them fit.
 *
 * <p>A box always names its leaf, which is what lets one reference on a catalogue
 * entry yield both numbers the import needs: pieces per strip from the leaf,
 * strips per box from here.
 *
 * <p>Part of the catalogue, not of any pharmacy: it carries no branch.
 */
@Entity
@Table(name = "GLOBAL_BOX")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlobalBox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PID_GLOBAL_BOX")
    private Long pidGlobalBox;

    @Column(name = "BOX_SIZE_TITLE", nullable = false, length = 60)
    private String boxSizeTitle;

    @Column(name = "PID_GLOBAL_LEAF", nullable = false)
    private Long pidGlobalLeaf;

    @Column(name = "NO_OF_LEAF", nullable = false)
    private Integer noOfLeaf;
}
