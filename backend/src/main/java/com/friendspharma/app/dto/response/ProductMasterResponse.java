package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Full Product Master detail payload (View / Edit / after Create / after Update).
 *
 * <p>Master-data references are returned as nested {@link Ref} objects so the UI
 * can render a label without a second round trip, while still holding the id it
 * must post back.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductMasterResponse {

    /* ── Identity ─────────────────────────────────────────────────────────── */
    private Long   id;
    /** System-generated, immutable. */
    private String productCode;
    private String productName;
    private String sku;

    /** PHARMA or GENERAL. Decides whether SKU and generic are shown as required. */
    private String productType;
    private String barcode;

    /* ── Master-data references ───────────────────────────────────────────── */
    private Ref generic;
    private Ref company;
    private Ref category;
    private Ref subCategory;
    private Ref unit;

    /** Box carries noOfPcs — derived, never stored on the product row. */
    private BoxRef box;

    /* ── Pricing ──────────────────────────────────────────────────────────── */
    private BigDecimal mrp;
    private BigDecimal conversionRate;
    /** Calculated: MRP − (MRP × conversionRate / 100). */
    private BigDecimal tpPrice;
    private BigDecimal vatPct;
    /** Calculated: tpPrice × vatPct / 100. */
    private BigDecimal igta;
    /** Calculated: tpPrice + (tpPrice × vatPct / 100). */
    private BigDecimal buyPrice;
    private BigDecimal discountPct;

    /* ── Inventory control ────────────────────────────────────────────────── */
    private BigDecimal reorderLevel;
    private BigDecimal safetyStock;
    private BigDecimal minimumStock;
    private BigDecimal maximumStock;
    private BigDecimal currentStock;
    /** OK / LOW / OUT — derived from currentStock against reorderLevel. */
    private String     stockStatus;

    /* ── Tracking & compliance ────────────────────────────────────────────── */
    private Boolean batchTracking;
    private Boolean expiryTracking;
    private Boolean prescriptionRequired;

    /* ── Presentation ─────────────────────────────────────────────────────── */
    /** Public URL, or null when the product has no image. */
    private String productImage;
    private String imgColor;

    /* ── Status & delete policy ───────────────────────────────────────────── */
    /** ACTIVE / INACTIVE. */
    private String  status;
    /**
     * False when the product has transaction history. The list grid uses this to
     * hide the Delete action so the user is never offered an operation that will
     * be refused with a 409.
     */
    private Boolean deletable;
    /** Populated only when deletable is false — e.g. "12 sales, 3 purchases". */
    private String  deleteBlockedReason;

    /* ── [V66] Versioning ─────────────────────────────────────────────────── */
    /** 1 for the original, one more for each edit. */
    private Integer       versionNo;
    /** The first version's id — the same on every version of one product. */
    private Long          rootProductId;
    /** The version that replaced this one. Null on the current version. */
    private Long          supersededBy;
    private LocalDateTime supersededDate;
    private String        supersededByUser;
    /** Convenience for the client: this is the version the shelf means. */
    private Boolean       currentVersion;

    /* ── Audit ────────────────────────────────────────────────────────────── */
    private String        createdBy;
    private LocalDateTime createdDate;
    private String        updatedBy;
    private LocalDateTime updatedDate;

    /** Generic {id, name} reference to a master-data row. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Ref {
        private Long   id;
        private String name;

        public static Ref of(Long id, String name) {
            return id == null ? null : Ref.builder().id(id).name(name).build();
        }
    }

    /**
     * Box reference. {@code noOfPcs} is computed as
     * POS_BOX_INFO.NO_OF_LEAF × POS_LEAF_INFO.NO_OF_PCS and exists for display
     * only — it is deliberately not a product column.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BoxRef {
        private Long    id;
        private String  name;
        private Integer noOfPcs;
        private Integer noOfLeaf;
        private Integer pcsPerLeaf;
    }
}
