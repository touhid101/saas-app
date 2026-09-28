package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A selling unit in the catalogue.
 *
 * <p>Part of the catalogue, not of any pharmacy. It carries no branch: the rows a
 * pharmacy works with are the copies the import creates, and those carry theirs.
 * Editing a name here renames it in the catalogue and on nobody's shelf.
 */
@Entity
@Table(name = "GLOBAL_UNIT")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlobalUnit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PID_GLOBAL_UNIT")
    private Long pidGlobalUnit;

    @Column(name = "UNIT_NAME", nullable = false, length = 50)
    private String unitName;
}
