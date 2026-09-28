package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One medicine in the Super Admin's catalogue.
 *
 * <p>Not a product. A product belongs to a pharmacy and has a price, a shelf and
 * a supplier; this is the description a pharmacy copies to make one. Importing
 * writes a real {@link PosProductInfo} carrying that branch, and from that moment
 * the two are independent — editing the catalogue does not reach into anybody's
 * shelf, which is the whole reason not to have branches read this table directly.
 *
 * <p>The prices are called <em>suggested</em> for the same reason: they are where
 * a pharmacy starts, not what it must charge.
 */
@Entity
@Table(name = "GLOBAL_PRODUCT")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlobalProduct extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_GLOBAL_PRODUCT")
    @SequenceGenerator(name = "SQ_GLOBAL_PRODUCT", sequenceName = "PHARMA.SQ_GLOBAL_PRODUCT", allocationSize = 1)
    @Column(name = "PID_GLOBAL_PRODUCT")
    private Long pidGlobalProduct;

    @Column(name = "PRODUCT_NAME", nullable = false, length = 200)
    private String productName;

    @Column(name = "GENERIC_NAME", length = 200)
    private String genericName;

    /**
     * PHARMA or GENERAL. The catalogue carries non-medicine too, and a general
     * item has no generic name to give.
     */
    /* Marked for the same reason as PosProductInfo.productType: @Builder skips
       an unmarked initialiser. Nothing builds a GlobalProduct today, so this
       is the trap closed rather than a bug fixed. */
    @Builder.Default
    @Column(name = "PRODUCT_TYPE", nullable = false, length = 10)
    private String productType = "PHARMA";

    @Column(name = "COMPANY_NAME", length = 150)
    private String companyName;

    /* ── the catalogue's own master lists ──
       The FK is the truth; the *_NAME column beside it is written from the FK on
       save. The names stay because the import reads them, and that path is
       proven and tenancy-critical — this change must not touch it. */
    @Column(name = "PID_GLOBAL_COMPANY")
    private Long pidGlobalCompany;

    @Column(name = "PID_GLOBAL_GENERIC")
    private Long pidGlobalGeneric;

    @Column(name = "PID_GLOBAL_CATEGORY")
    private Long pidGlobalCategory;

    @Column(name = "PID_GLOBAL_SUB_CATEGORY")
    private Long pidGlobalSubCategory;

    @Column(name = "PID_GLOBAL_UNIT")
    private Long pidGlobalUnit;

 /** The strip this medicine comes in. */
       @Column(name = "PID_GLOBAL_LEAF")
       private Long pidGlobalLeaf;
   
       /** The box, which names its own leaf. Optional: not everything comes boxed. */
       @Column(name = "PID_GLOBAL_BOX")
       private Long pidGlobalBox;

    @Column(name = "CATEGORY_NAME", length = 100)
    private String categoryName;

    /** Resolved under {@link #categoryName} in the receiving pharmacy on import. */
    @Column(name = "SUB_CATEGORY_NAME", length = 100)
    private String subCategoryName;

    @Column(name = "STRENGTH", length = 60)
    private String strength;

    /** Where a pharmacy starts, not what it must charge. */
    @Column(name = "SUGGESTED_MRP")
    private BigDecimal suggestedMrp;

    @Column(name = "SUGGESTED_TP")
    private BigDecimal suggestedTp;

    /**
     * V89: what POS_PRODUCT_INFO has always carried, so an import lands with
     * them filled. IGTA is the VAT amount on the trade price; the buy price is
     * the trade price with it. Nullable — a catalogue entry with no known VAT
     * is still an entry. Suggested, like the prices: the pharmacy owns its copy.
     */
    @Column(name = "IGTA")
    private BigDecimal igta;

    @Column(name = "BUY_PRICE")
    private BigDecimal buyPrice;

    @Column(name = "UNIT_NAME", length = 50)
    private String unitName;

    @Column(name = "PCS_PER_LEAF")
    private Integer pcsPerLeaf;

    @Column(name = "LEAF_PER_BOX")
    private Integer leafPerBox;

    @Column(name = "PRESCRIPTION_FLAG", length = 1)
    private String prescriptionFlag;

    @Column(name = "BARCODE", length = 60)
    private String barcode;

    @Column(name = "PRODUCT_CODE", length = 60)
    private String productCode;

    @Column(name = "FILE_NAME", length = 200)
    private String fileName;

    @Column(name = "FILE_MIME_TYPE", length = 100)
    private String fileMimeType;

    /**
     * The image's filename on disk, not the image.
     *
     * <p>Matching what POS_PRODUCT_INFO already does. A catalogue of a few thousand
     * medicines with a photograph in each row is tens of megabytes read every time
     * somebody draws a table of names — and PostgreSQL maps a byte[] LOB to an oid
     * rather than bytea, which is its own small argument for not doing it.
     */
    @Column(name = "PRODUCT_IMAGE", length = 300)
    private String productImage;

    @Column(name = "IS_ACTIVE", nullable = false)
    private Integer isActive;
}
