package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A generic (active ingredient) in the catalogue.
 *
 * <p>Part of the catalogue, not of any pharmacy. It carries no branch: the rows a
 * pharmacy works with are the copies the import creates, and those carry theirs.
 * Editing a name here renames it in the catalogue and on nobody's shelf.
 */
@Entity
@Table(name = "GLOBAL_GENERIC")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlobalGeneric extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PID_GLOBAL_GENERIC")
    private Long pidGlobalGeneric;

    @Column(name = "GENERIC_NAME", nullable = false, length = 150)
    private String genericName;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;
}
