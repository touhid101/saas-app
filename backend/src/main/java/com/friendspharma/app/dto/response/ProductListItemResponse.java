package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Slim row for the Product List grid.
 *
 * <p>Separate from {@link ProductMasterResponse} on purpose: the grid renders
 * thousands of rows and does not need audit fields, stock ladders or the full
 * nested master-data objects. Keeping the list payload narrow is what lets the
 * page stay responsive at scale.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductListItemResponse {

    private Long   id;
    private String productCode;
    private String productName;
    private String sku;

    /** PHARMA or GENERAL — a column on the list. */
    private String productType;
    private String barcode;

    private String genericName;
    private String companyName;
    private String categoryName;
    private String unitName;

    private BigDecimal mrp;
    private BigDecimal tpPrice;
    private BigDecimal buyPrice;

    private BigDecimal currentStock;
    private BigDecimal reorderLevel;
    /** OK / LOW / OUT. */
    private String     stockStatus;

    private Boolean batchTracking;
    private Boolean expiryTracking;
    private Boolean prescriptionRequired;

    /** Public URL or null. */
    private String productImage;
    private String imgColor;

    /** ACTIVE / INACTIVE. */
    private String  status;
    /** Drives whether the grid shows the Delete action for this row. */
    private Boolean deletable;
}
