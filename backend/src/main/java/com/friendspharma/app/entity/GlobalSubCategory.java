package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A sub-category, always beneath one category.
 *
 * <p>Unique on (category, name): two categories may each have a "Tablet",
 * and they are different things.
 *
 * <p>Part of the catalogue, not of any pharmacy. It carries no branch: the rows a
 * pharmacy works with are the copies the import creates, and those carry theirs.
 * Editing a name here renames it in the catalogue and on nobody's shelf.
 */
@Entity
@Table(name = "GLOBAL_SUB_CATEGORY")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlobalSubCategory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PID_GLOBAL_SUB_CATEGORY")
    private Long pidGlobalSubCategory;

    @Column(name = "PID_GLOBAL_CATEGORY", nullable = false)
    private Long pidGlobalCategory;

    @Column(name = "SUB_CATEGORY_NAME", nullable = false, length = 100)
    private String subCategoryName;
}
