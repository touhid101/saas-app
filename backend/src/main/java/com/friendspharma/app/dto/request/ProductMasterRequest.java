package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Product Master create/update payload.
 *
 * <p>Deliberately absent: {@code productCode}, {@code tpPrice}, {@code igta},
 * {@code buyPrice}. Those are server-generated or server-calculated. Leaving
 * them off the DTO entirely means a malicious client cannot even attempt to set
 * them — Jackson has nowhere to bind the value, so there is no field to ignore
 * and no code path that could later start trusting one.
 *
 * <p>Bean Validation covers per-field shape. Cross-field business rules
 * (uniqueness, master-data references, the stock ladder) are enforced in
 * ProductValidator because they need repository access.
 */
@Data
public class ProductMasterRequest {

    /* ── Section 1: Basic information ─────────────────────────────────────── */

    /** PHARMA (medicine) or GENERAL (everything else). Defaults to PHARMA. */
    @Pattern(regexp = "^(PHARMA|GENERAL)?$", message = "Product type must be PHARMA or GENERAL.")
    private String productType;

    @NotBlank(message = "Product name is required.")
    @Size(max = 150, message = "Product name must not exceed 150 characters.")
    private String productName;

    /**
     * Required for PHARMA, optional for GENERAL — so the rule cannot live here.
     *
     * <p>Bean Validation runs before anything has looked at {@code productType},
     * which makes a field-level @NotNull unable to express "unless this is a bar
     * of soap". ProductValidator applies it once the type is known.
     */
    private Long genericId;

    @NotNull(message = "Company is required.")
    private Long companyId;

    @NotNull(message = "Category is required.")
    private Long categoryId;

    /** Optional — the existing schema supports sub-categories under a category. */
    private Long subCategoryId;

    /**
     * Required for PHARMA, optional for GENERAL — conditional, so enforced in
     * ProductValidator rather than here. The shape rules below still apply to
     * whatever is supplied; the pattern allows empty so that a General product
     * sending "" is a matter for the trim-to-null in the service, not a 400.
     */
    @Size(max = 50, message = "SKU must not exceed 50 characters.")
    @Pattern(regexp = "^[A-Za-z0-9._/-]*$",
             message = "SKU may contain only letters, digits and the characters . _ / -")
    private String sku;

    @NotNull(message = "Unit is required.")
    private Long unitId;

    /** Optional. Drives the display-only No Of Pcs, which is never stored. */
    private Long boxId;

    @Size(max = 30, message = "Barcode must not exceed 30 characters.")
    @Pattern(regexp = "^[A-Za-z0-9-]*$",
             message = "Barcode may contain only letters, digits and hyphens.")
    private String barcode;

    /* ── Section 2: Pricing ───────────────────────────────────────────────── */

    @NotNull(message = "MRP is required.")
    @DecimalMin(value = "0.0", message = "MRP must be greater than or equal to zero.")
    @Digits(integer = 8, fraction = 4, message = "MRP may have at most 8 digits and 4 decimals.")
    private BigDecimal mrp;

    /** Defaults to 25.037 when omitted. Never overwritten on edit. */
    @DecimalMin(value = "0.0", message = "Conversion rate must be greater than or equal to zero.")
    @DecimalMax(value = "100.0", message = "Conversion rate cannot exceed 100.")
    @Digits(integer = 6, fraction = 4, message = "Conversion rate may have at most 6 digits and 4 decimals.")
    private BigDecimal conversionRate;

    /** Defaults to 17.40 when omitted. Never overwritten on edit. */
    @DecimalMin(value = "0.0", message = "VAT % must be greater than or equal to zero.")
    @DecimalMax(value = "100.0", message = "VAT % cannot exceed 100.")
    @Digits(integer = 4, fraction = 4, message = "VAT % may have at most 4 digits and 4 decimals.")
    private BigDecimal vatPct;

    /** Optional trade discount, unrelated to the calculated price chain. */
    @DecimalMin(value = "0.0", message = "Discount % must be greater than or equal to zero.")
    @DecimalMax(value = "100.0", message = "Discount % cannot exceed 100.")
    private BigDecimal discountPct;

    /* ── Section 3: Inventory control ─────────────────────────────────────── */

    @DecimalMin(value = "0.0", message = "Reorder level must be greater than or equal to zero.")
    @Digits(integer = 10, fraction = 4)
    private BigDecimal reorderLevel;

    @DecimalMin(value = "0.0", message = "Safety stock must be greater than or equal to zero.")
    @Digits(integer = 10, fraction = 4)
    private BigDecimal safetyStock;

    @DecimalMin(value = "0.0", message = "Minimum stock must be greater than or equal to zero.")
    @Digits(integer = 10, fraction = 4)
    private BigDecimal minimumStock;

    @DecimalMin(value = "0.0", message = "Maximum stock must be greater than or equal to zero.")
    @Digits(integer = 10, fraction = 4)
    private BigDecimal maximumStock;

    /* ── Section 4: Tracking & compliance ─────────────────────────────────── */

    /** Defaults to false. */
    private Boolean batchTracking;

    /** Defaults to false. */
    private Boolean expiryTracking;

    /** Defaults to false. */
    private Boolean prescriptionRequired;

    /* ── Section 6: Status ────────────────────────────────────────────────── */

    /**
     * ACTIVE or INACTIVE. Defaults to ACTIVE on create.
     * Mapped to the STATUS Y/N column used across the rest of the schema.
     */
    @Pattern(regexp = "^(ACTIVE|INACTIVE)$", message = "Status must be ACTIVE or INACTIVE.")
    private String status;

    /* ── Presentation ─────────────────────────────────────────────────────── */

    /** Hex colour swatch shown on POS cards when no image is uploaded. */
    @Pattern(regexp = "^#?[0-9A-Fa-f]{6}$", message = "Colour must be a 6-digit hex value.")
    private String imgColor;
}
