package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Maps to POS_PRODUCT_INFO — the central product master.
 * Extended: PID_SUB_CATEGORY, IMG_COLOR, PRESCRIPTION_FLAG.
 *
 * V4 (Product Master) added: PRODUCT_CODE, PID_UNIT_ID, REORDER_LEVEL,
 * SAFETY_STOCK, MINIMUM_STOCK, MAXIMUM_STOCK, BATCH_TRACKING, EXPIRY_TRACKING,
 * PRODUCT_IMAGE; changed IGTA from VARCHAR2(50) to a numeric amount; dropped
 * NO_OF_PCS (pieces-per-box is derived from POS_BOX_INFO × POS_LEAF_INFO and
 * must not be duplicated here).
 */
@Entity @Table(name = "POS_PRODUCT_INFO", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosProductInfo extends BaseEntity {

    @Id @Column(name = "PID_PRODUCT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_prod")
    @SequenceGenerator(name = "seq_prod", sequenceName = "SEQ_PID_PRODUCT", allocationSize = 1)
    private Long pidProduct;

    /* ── [V66] versioning ──
       An edit does not overwrite a product; it writes a new one and retires the
       old. These three columns are the chain. See ProductServiceImpl.update. */

    /**
     * The first version's id, shared by every version of the same product and
     * never changed. Equal to {@link #pidProduct} on a product nobody has edited.
     *
     * <p>One indexed column answers "every version of this product" without
     * walking {@link #supersededBy} backwards a step at a time.
     */
    @Column(name = "PID_ROOT_PRODUCT")
    private Long pidRootProduct;

    /** 1 for the original, one more for each edit. */
    @Builder.Default
    @Column(name = "VERSION_NO", nullable = false)
    private Integer versionNo = 1;

    /**
     * The version that replaced this one; null on the current version.
     *
     * <p>A database check constraint refuses a row that is superseded and still
     * active, so "one live version per chain" cannot be half-applied by a writer
     * that sets one field and forgets the other.
     */
    @Column(name = "SUPERSEDED_BY")
    private Long supersededBy;

    @Column(name = "SUPERSEDED_DATE")
    private java.time.LocalDateTime supersededDate;

    @Column(name = "SUPERSEDED_BY_USER", length = 50)
    private String supersededByUser;

    /**
     * [V4] System-generated business code (PRD######).
     * Derived from PID_PRODUCT by ProductServiceImpl — never accepted from a client.
     *
     * <p>Deliberately NOT {@code updatable = false}. The code is derived from the
     * generated primary key, so it can only be assigned by the UPDATE that
     * follows the INSERT; marking the column non-updatable excluded it from that
     * statement and the code was silently never persisted.
     *
     * <p>Immutability is enforced above the entity instead: ProductMasterRequest
     * has no productCode field, and ProductServiceImpl.update() never touches it.
     */
    @Column(name = "PRODUCT_CODE", length = 30)
    private String productCode;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    /**
     * The catalogue row this was copied from, where it was.
     *
     * <p>Kept so the screen can say where a product came from, and so a second
     * import knows not to copy it again — though it is the unique index on
     * (branch, catalogue row) that actually enforces that, because two
     * administrators importing at once would both pass an application check.
     *
     * <p>Null for a product the pharmacy typed in itself, which is most of them.
     */
    @Column(name = "PID_GLOBAL_PRODUCT")
    private Long pidGlobalProduct;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_CATEGORY")
    private Long pidCategory;

    /** [NEW] Sub-category FK */
    @Column(name = "PID_SUB_CATEGORY")
    private Long pidSubCategory;

    @Column(name = "PID_GENERIC")
    private Long pidGeneric;

    @Column(name = "PID_BOX")
    private Long pidBox;

    /**
     * Denormalised unit NAME (e.g. "PCS", "BOX"). Retained because the POS and
     * transaction modules read this string directly. Kept in sync with pidUnitId.
     */
    @Column(name = "PID_UNIT", length = 20)
    private String pidUnit;

    /** [V4] Real FK to POS_PRODUCT_UNIT.PID_UNIT. */
    @Column(name = "PID_UNIT_ID")
    private Long pidUnitId;

    @Column(name = "PRODUCT_NAME", length = 150, nullable = false)
    private String productName;

    @Column(name = "BARCODE", length = 30)
    private String barcode;

    @Column(name = "SKU", length = 50)
    private String sku;

    /**
     * PHARMA or GENERAL.
     *
     * <p>A pharmacy sells soap and baby food across the same counter as medicine,
     * and those have no generic name — "Generic: none" is not a fact about a bar
     * of soap. The type decides whether {@link #sku} and {@link #pidGeneric} are
     * demanded when somebody enters a product; everything else about a product
     * behaves the same either way, because a general item is still stocked,
     * priced and sold.
     *
     * <p>Defaulted rather than left null so a row written by the bulk importer or
     * by hand is a medicine unless it says otherwise, which is what this table
     * has always held.
     */
    /* @Builder.Default, and it is not optional. Lombok's @Builder ignores a
       field initialiser unless it is marked, so PosProductInfo.builder() was
       producing productType = null and the catalogue import died on
       "Product type is required." — the not-null message the database
       generated. The initialiser above documented an intent the builder never
       honoured. */
    @Builder.Default
    @Column(name = "PRODUCT_TYPE", nullable = false, length = 10)
    private String productType = "PHARMA";

    @Column(name = "MRP_PRICE", precision = 12, scale = 4)
    private BigDecimal mrpPrice;

    @Column(name = "BUY_PRICE", precision = 12, scale = 4)
    private BigDecimal buyPrice;

    @Column(name = "TP_PRICE", precision = 12, scale = 4)
    private BigDecimal tpPrice;

    @Column(name = "VAT_PCT", precision = 8, scale = 4)
    private BigDecimal vatPct;

    @Column(name = "DISCOUNT_PCT", precision = 8, scale = 4)
    private BigDecimal discountPct;

    @Column(name = "CONVERSION_RATE", precision = 10, scale = 4)
    private BigDecimal conversionRate;

    /**
     * [V4] Calculated: ROUND(TP_PRICE × VAT_PCT / 100, 2).
     * Was VARCHAR2(50) in the Oracle import — corrected to a numeric amount.
     */
    @Column(name = "IGTA", precision = 14, scale = 4)
    private BigDecimal igta;

    /* NO_OF_PCS removed in V4 — pieces-per-box is derived from
       POS_BOX_INFO.NO_OF_LEAF × POS_LEAF_INFO.NO_OF_PCS and is never stored here. */

    /* ── Sales percentages (Sales%) ──────────────────────────────────────────
       Discount off MRP used to derive each unit's sale price, per the SRS:
           Unit Sales Price = MRP − (MRP × Sales Percentage / 100)

       [V8] Widened from Integer to BigDecimal — as whole numbers a rate like
       12.5% silently truncated to 12. Null means the unit is not priced, which
       the SRS treats as not sellable in that unit.                            */

    /** BOX %, regular customer. */
    @Column(name = "SALES_PER", precision = 8, scale = 4)
    private BigDecimal salesPer;

    /** STRIP/LEAF %, regular customer. */
    @Column(name = "SALES_PER_LEAF", precision = 8, scale = 4)
    private BigDecimal salesPerLeaf;

    /** PCS %, regular customer. */
    @Column(name = "SALES_PER_PCS", precision = 8, scale = 4)
    private BigDecimal salesPerPcs;

    /** BOX %, special customer. */
    @Column(name = "SALES_PER_SPC", precision = 8, scale = 4)
    private BigDecimal salesPerSpc;

    /** STRIP/LEAF %, special customer. */
    @Column(name = "SALES_PER_SPC_LEAF", precision = 8, scale = 4)
    private BigDecimal salesPerSpcLeaf;

    /** PCS %, special customer. */
    @Column(name = "SALES_PER_SPC_RET", precision = 8, scale = 4)
    private BigDecimal salesPerSpcRet;

    /**
     * Legacy low-stock threshold. The Inventory module and
     * {@code findLowStockProducts()} still read it, so Product Master keeps it
     * mirrored with {@link #reorderLevel} on every write.
     */
    @Column(name = "ALERT_QTY")
    private Integer alertQty;

    /** [V4] Stock level that triggers reorder. Authoritative; mirrors into alertQty. */
    @Column(name = "REORDER_LEVEL", precision = 14, scale = 4)
    private BigDecimal reorderLevel;

    /** [V4] Reserve buffer stock. */
    @Column(name = "SAFETY_STOCK", precision = 14, scale = 4)
    private BigDecimal safetyStock;

    /** [V4] Absolute operational minimum. */
    @Column(name = "MINIMUM_STOCK", precision = 14, scale = 4)
    private BigDecimal minimumStock;

    /** [V4] Maximum desired stock. 0 / null = no ceiling configured. */
    @Column(name = "MAXIMUM_STOCK", precision = 14, scale = 4)
    private BigDecimal maximumStock;

    /** [V4] Y = batch number mandatory in purchase/inventory transactions. */
    @Column(name = "BATCH_TRACKING", length = 1)
    private String batchTracking;

    /** [V4] Y = expiry date mandatory in purchase/inventory transactions. */
    @Column(name = "EXPIRY_TRACKING", length = 1)
    private String expiryTracking;

    @Column(name = "STOCK_QTY")
    private BigDecimal stockQty;

    /**
     * [V4] Stored image filename on disk (never a client-supplied path).
     * Served from app.upload.product-image-url-prefix.
     */
    @Column(name = "PRODUCT_IMAGE", length = 300)
    private String productImage;

    /** Legacy product image BLOB — retained so pre-V4 rows keep rendering. */
    @Lob @Column(name = "IMAGE")
    private byte[] image;

    @Column(name = "FILE_NAME", length = 256)
    private String fileName;

    @Column(name = "FILE_MIME_TYPE", length = 200)
    private String fileMimeType;

    /** [NEW] Hex colour swatch for POS card (imgColor in React) */
    @Column(name = "IMG_COLOR", length = 10)
    private String imgColor;

    /** [NEW] Prescription required flag Y/N */
    @Column(name = "PRESCRIPTION_FLAG", length = 1)
    private String prescriptionFlag;

    /** Computed transient fields */
    @Transient private Integer pcsPerBox;
    @Transient private Integer pcsPerLeaf;
    @Transient private Integer leavesPerBox;

    // ── Navigation associations (read-only, mirror FK columns) ───────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_COMPANY", insertable = false, updatable = false)
    private PosCompanyInfo companyInfo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_GENERIC", insertable = false, updatable = false)
    private PosGenericInfo genericInfo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_CATEGORY", insertable = false, updatable = false)
    private PosProductCategory categoryInfo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_SUB_CATEGORY", insertable = false, updatable = false)
    private PosProductSubCategory subCategoryInfo;

    // ── Plain Java aliases for the POS service layer ──────────────────────
    // NOTE: getProductCode() and getReorderLevel() used to live here as aliases
    // for SKU and ALERT_QTY. V4 promoted both to real columns, so Lombok now
    // generates the accessors and the aliases were removed — callers get the
    // genuine product code and reorder level instead of the stand-ins.

    public String     getBarcodeNo()       { return barcode; }
    public Integer    getIsActive()        { return "Y".equals(getStatus()) ? 1 : 0; }

    /** Stored image reference — the V4 disk filename, else the legacy BLOB filename. */
    public String     getProductImageUrl() { return productImage != null ? productImage : fileName; }

    public BigDecimal getMrpBox()          { return mrpPrice; }
    public BigDecimal getMrpStrip()        { return tpPrice; }
    public BigDecimal getMrpPcs()          { return buyPrice; }
    /*
     * getPcsPerStrip() and getStripPerBox() used to return SALES_PER_LEAF and
     * SALES_PER, treating a percentage as a unit count — a 12% box discount
     * would have been reported to the POS as "12 strips per box".
     *
     * Pieces per unit is derived from POS_BOX_INFO.NO_OF_LEAF ×
     * POS_LEAF_INFO.NO_OF_PCS, which the caller resolves; these columns are
     * pricing and carry no quantity meaning. The accessors are gone rather than
     * re-pointed, so nothing can keep reading a percentage as a count.
     */
    public BigDecimal getCurrentStock()    { return stockQty; }
    public BigDecimal getPurchasePrice()   { return buyPrice; }

    /** True when the product is ACTIVE (STATUS = 'Y'). */
    public boolean isActiveProduct()       { return "Y".equals(getStatus()); }
}
