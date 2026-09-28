package com.friendspharma.app.mapper;

import com.friendspharma.app.dto.response.ProductListItemResponse;
import com.friendspharma.app.dto.response.ProductMasterResponse;
import com.friendspharma.app.dto.response.ProductMasterResponse.BoxRef;
import com.friendspharma.app.dto.response.ProductMasterResponse.Ref;
import com.friendspharma.app.entity.*;

import java.math.BigDecimal;

/**
 * Entity → DTO mapping for Product Master.
 *
 * <p>Kept separate from the legacy {@link ProductMapper}, which serves the older
 * MasterData product shape (base64 images, salesPer* fields, alertQty). The two
 * describe the same table for different consumers; merging them would force one
 * caller's field names onto the other.
 *
 * <p>Two conversions worth naming:
 * <ul>
 *   <li><b>Status</b> — the schema stores Y/N; the API speaks ACTIVE/INACTIVE.
 *       The translation lives here so no page has to know about Y/N.</li>
 *   <li><b>noOfPcs</b> — computed as box.noOfLeaf × leaf.noOfPcs. Display only;
 *       the product row deliberately has no such column.</li>
 * </ul>
 */
public final class ProductMasterMapper {

    private ProductMasterMapper() {}

    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    /* ── Status helpers ──────────────────────────────────────────────────── */

    public static String toApiStatus(String dbStatus)  { return "Y".equals(dbStatus) ? STATUS_ACTIVE : STATUS_INACTIVE; }
    public static String toDbStatus(String apiStatus)  { return STATUS_INACTIVE.equalsIgnoreCase(apiStatus) ? "N" : "Y"; }

    public static boolean toBool(String yn)            { return "Y".equalsIgnoreCase(yn); }
    public static String  toYn(Boolean value)          { return Boolean.TRUE.equals(value) ? "Y" : "N"; }

    /* ── Detail ──────────────────────────────────────────────────────────── */

    public static ProductMasterResponse toResponse(PosProductInfo e,
                                                   PosGenericInfo generic,
                                                   PosCompanyInfo company,
                                                   PosProductCategory category,
                                                   PosProductSubCategory subCategory,
                                                   PosProductUnit unit,
                                                   PosBoxInfo box,
                                                   PosLeafInfo leaf,
                                                   String imageUrl,
                                                   boolean deletable,
                                                   String deleteBlockedReason) {

        BigDecimal stock   = nvl(e.getStockQty());
        BigDecimal reorder = resolveReorderLevel(e);

        return ProductMasterResponse.builder()
                .id(e.getPidProduct())
                .productCode(e.getProductCode())
                .productName(e.getProductName())
                .sku(e.getSku())
                .productType(e.getProductType())
                .barcode(e.getBarcode())

                .generic(Ref.of(e.getPidGeneric(),     generic     != null ? generic.getGenericName()         : null))
                .company(Ref.of(e.getPidCompany(),     company     != null ? company.getCompanyName()         : null))
                .category(Ref.of(e.getPidCategory(),   category    != null ? category.getCategoryName()       : null))
                .subCategory(Ref.of(e.getPidSubCategory(), subCategory != null ? subCategory.getSubCategoryName() : null))
                .unit(Ref.of(e.getPidUnitId(),         unit        != null ? unit.getUnitName() : e.getPidUnit()))
                .box(toBoxRef(box, leaf))

                .mrp(e.getMrpPrice())
                .conversionRate(e.getConversionRate())
                .tpPrice(e.getTpPrice())
                .vatPct(e.getVatPct())
                .igta(e.getIgta())
                .buyPrice(e.getBuyPrice())
                .discountPct(e.getDiscountPct())

                .reorderLevel(reorder)
                .safetyStock(nvl(e.getSafetyStock()))
                .minimumStock(nvl(e.getMinimumStock()))
                .maximumStock(nvl(e.getMaximumStock()))
                .currentStock(stock)
                .stockStatus(stockStatus(stock, reorder))

                .batchTracking(toBool(e.getBatchTracking()))
                .expiryTracking(toBool(e.getExpiryTracking()))
                .prescriptionRequired(toBool(e.getPrescriptionFlag()))

                .productImage(imageUrl)
                .imgColor(e.getImgColor())

                .status(toApiStatus(e.getStatus()))
                .deletable(deletable)
                .deleteBlockedReason(deleteBlockedReason)

                /* [V66] The chain. rootProductId falls back to the row's own id
                   so a product written before versioning existed still reads as
                   version 1 of itself rather than as a version of nothing. */
                .versionNo(e.getVersionNo() == null ? 1 : e.getVersionNo())
                .rootProductId(e.getPidRootProduct() != null
                        ? e.getPidRootProduct() : e.getPidProduct())
                .supersededBy(e.getSupersededBy())
                .supersededDate(e.getSupersededDate())
                .supersededByUser(e.getSupersededByUser())
                .currentVersion(e.getSupersededBy() == null)

                .createdBy(e.getInsertedBy())
                .createdDate(e.getInsertedDate())
                .updatedBy(e.getUpdatedBy())
                .updatedDate(e.getUpdatedDate())
                .build();
    }

    /* ── List row ────────────────────────────────────────────────────────── */

    public static ProductListItemResponse toListItem(PosProductInfo e,
                                                     String genericName,
                                                     String companyName,
                                                     String categoryName,
                                                     String unitName,
                                                     String imageUrl,
                                                     boolean deletable) {
        BigDecimal stock   = nvl(e.getStockQty());
        BigDecimal reorder = resolveReorderLevel(e);

        return ProductListItemResponse.builder()
                .id(e.getPidProduct())
                .productCode(e.getProductCode())
                .productName(e.getProductName())
                .sku(e.getSku())
                .productType(e.getProductType())
                .barcode(e.getBarcode())
                .genericName(genericName)
                .companyName(companyName)
                .categoryName(categoryName)
                .unitName(unitName != null ? unitName : e.getPidUnit())
                .mrp(e.getMrpPrice())
                .tpPrice(e.getTpPrice())
                .buyPrice(e.getBuyPrice())
                .currentStock(stock)
                .reorderLevel(reorder)
                .stockStatus(stockStatus(stock, reorder))
                .batchTracking(toBool(e.getBatchTracking()))
                .expiryTracking(toBool(e.getExpiryTracking()))
                .prescriptionRequired(toBool(e.getPrescriptionFlag()))
                .productImage(imageUrl)
                .imgColor(e.getImgColor())
                .status(toApiStatus(e.getStatus()))
                .deletable(deletable)
                .build();
    }

    /* ── Derived values ──────────────────────────────────────────────────── */

    /**
     * Pieces per box = box.noOfLeaf × leaf.noOfPcs.
     * Returns null rather than 0 when either side is unknown, so the UI shows
     * an em-dash instead of a misleading zero.
     */
    private static BoxRef toBoxRef(PosBoxInfo box, PosLeafInfo leaf) {
        if (box == null) return null;
        Integer noOfLeaf   = box.getNoOfLeaf();
        Integer pcsPerLeaf = leaf != null ? leaf.getNoOfPcs() : null;
        Integer noOfPcs    = (noOfLeaf != null && pcsPerLeaf != null) ? noOfLeaf * pcsPerLeaf : null;

        return BoxRef.builder()
                .id(box.getPidBox())
                .name(box.getBoxSizeTitle())
                .noOfLeaf(noOfLeaf)
                .pcsPerLeaf(pcsPerLeaf)
                .noOfPcs(noOfPcs)
                .build();
    }

    /**
     * Prefers the V4 REORDER_LEVEL, falling back to the legacy ALERT_QTY so
     * products created before the migration still classify correctly.
     */
    private static BigDecimal resolveReorderLevel(PosProductInfo e) {
        if (e.getReorderLevel() != null) return e.getReorderLevel();
        return e.getAlertQty() != null ? BigDecimal.valueOf(e.getAlertQty()) : BigDecimal.ZERO;
    }

    private static String stockStatus(BigDecimal stock, BigDecimal reorderLevel) {
        if (stock.compareTo(BigDecimal.ZERO) <= 0)      return "OUT";
        if (stock.compareTo(nvl(reorderLevel)) <= 0)    return "LOW";
        return "OK";
    }

    private static BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
