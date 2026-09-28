package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a navigable module/menu item in the application.
 * Supports parent-child hierarchy for grouped menus.
 */
@Entity
@Table(name = "ACL_MODULE_INFO",
        uniqueConstraints = @UniqueConstraint(name = "UQ_MODULE_CODE", columnNames = "MODULE_CODE"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AclModuleInfo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_ACL_MODULE")
    @SequenceGenerator(name = "SQ_ACL_MODULE", sequenceName = "SQ_ACL_MODULE", allocationSize = 1)
    @Column(name = "PID_MODULE")
    private Long pidModule;

    /** Unique code used by front-end to match routes, e.g. SALES, PURCHASE, REPORTS */
    @Column(name = "MODULE_CODE", nullable = false, length = 50)
    private String moduleCode;

    @Column(name = "MODULE_NAME", nullable = false, length = 100)
    private String moduleName;

    @Column(name = "MODULE_DESC", length = 300)
    private String moduleDesc;

    /** Front-end icon key, e.g. ShoppingCart, BarChart2 */
    @Column(name = "ICON_KEY", length = 60)
    private String iconKey;

    /** React Router path, e.g. /dashboard, /sales */
    @Column(name = "ROUTE_PATH", length = 200)
    private String routePath;

    /** Parent module for sub-menus; null = top-level */
    @Column(name = "PID_PARENT_MODULE")
    private Long pidParentModule;

    @Column(name = "SORT_ORDER")
    @Builder.Default
    private Integer sortOrder = 0;

    /** 1 = active, 0 = hidden */
    @Column(name = "IS_ACTIVE", nullable = false)
    @Builder.Default
    private Integer isActive = 1;

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PosPermissions> permissions = new ArrayList<>();
}
