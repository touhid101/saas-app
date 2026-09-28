package com.friendspharma.app.validation;

import com.friendspharma.app.dto.request.ProductMasterRequest;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.InactiveEntityException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Business-rule validation for Product Master.
 *
 * <p>Bean Validation on {@link ProductMasterRequest} covers per-field shape.
 * Everything here needs repository access or compares fields against each
 * other: master-data existence and active state, uniqueness, and the stock
 * ladder.
 *
 * <p>Kept separate from the service so the rules are readable in one place and
 * the create/update paths cannot drift apart — both call
 * {@link #validate(ProductMasterRequest, Long, Long)} with the same contract.
 */
@Component
@RequiredArgsConstructor
public class ProductValidator {

    /** Sentinel for "no row to exclude" on the create path. */
    private static final long NO_EXCLUSION = -1L;

    private final PosProductInfoRepository     productRepo;
    private final PosGenericInfoRepository     genericRepo;
    private final PosCompanyInfoRepository     companyRepo;
    private final PosProductCategoryRepository categoryRepo;
    private final PosProductSubCategoryRepository subCategoryRepo;
    private final PosProductUnitRepository     unitRepo;
    private final PosBoxInfoRepository         boxRepo;

    /**
     * Full validation pass.
     *
     * @param req        incoming payload (already Bean-Validated)
     * @param orgId      tenant, taken from the JWT — never from the request
     * @param productId  row being updated, or null on create
     */
    public ValidatedReferences validate(ProductMasterRequest req, Long orgId, Long productId) {
        long excludeId = productId != null ? productId : NO_EXCLUSION;

        validateTypeRules(req, productId);
        ValidatedReferences refs = validateMasterDataReferences(req, orgId);
        validateUniqueness(req, orgId, excludeId);
        validateStockLadder(req);
        validatePricingInputs(req);

        return refs;
    }

    /* ── Type ────────────────────────────────────────────────────────────── */

    /**
     * What the product type demands.
     *
     * <p>A medicine is identified by its generic and stocked by its SKU, so both
     * are required for PHARMA. A bar of soap has neither, and inventing them —
     * which is what the old unconditional rule forced people to do — puts noise
     * into the two fields most used to find a drug.
     *
     * <p>This is not mirrored by a database CHECK, deliberately: 70 of the
     * medicines already in this system have no SKU, having arrived through the
     * bulk importer rather than the form, and a constraint would make them
     * uneditable. See V62__product_type.sql. The rule therefore applies to what
     * somebody is entering now, where the value can actually be supplied.
     */
    private void validateTypeRules(ProductMasterRequest req, Long productId) {
        if (isGeneral(req)) return;

        // What the stored row already lacks, it may go on lacking.
        //
        // 70 of the medicines in this system have no SKU: they arrived through
        // the bulk importer and the catalogue copy, neither of which ever set
        // one. Demanding a SKU on every save would mean nobody could correct the
        // price of any of them without first inventing an identifier — a rule
        // introduced today making yesterday's data uneditable.
        //
        // So the requirement binds what is being *introduced*: a new product, or
        // an edit that removes a value the row used to have. An old gap is left
        // alone until somebody chooses to fill it.
        PosProductInfo existing = productId == null ? null
                : productRepo.findById(productId).orElse(null);

        /* SKU is optional. It was required for a pharma product, and 75 of the
           119 products in this database have none — every one imported from the
           catalogue or loaded in bulk. The identifier that matters is the
           product code, which the server generates and nobody can omit; a SKU is
           the supplier's or the shelf's own reference and plenty of medicines
           simply do not carry one. It is still unique when given. */
        if (req.getGenericId() == null && (existing == null || existing.getPidGeneric() != null)) {
            throw new ValidationException("Generic is required for a pharma product.");
        }
    }

    private static boolean isBlank(String s) {
        String t = trim(s);
        return t == null || t.isEmpty();
    }

    /** True when the payload is a general (non-medicine) product. */
    private static boolean isGeneral(ProductMasterRequest req) {
        return "GENERAL".equalsIgnoreCase(req.getProductType());
    }

    /* ── Master data ─────────────────────────────────────────────────────── */

    /**
     * Confirms every referenced master row exists, belongs to this tenant, and
     * is active. The tenant check matters as much as the existence check: without
     * it a user could attach another organisation's generic to their product by
     * guessing an id.
     */
    private ValidatedReferences validateMasterDataReferences(ProductMasterRequest req, Long orgId) {
        // A general product may have no generic at all. When one IS supplied it is
        // checked exactly as before — an optional field is still not a licence to
        // reference another tenant's row.
        PosGenericInfo generic = null;
        if (req.getGenericId() != null) {
            generic = genericRepo.findById(req.getGenericId())
                    .orElseThrow(() -> new ResourceNotFoundException("Generic", req.getGenericId()));
            requireSameOrg(generic.getPidOrg(), orgId, "Generic");
            if (!"Y".equals(generic.getStatus())) {
                throw new InactiveEntityException("Generic", generic.getGenericName());
            }
        }

        PosCompanyInfo company = companyRepo.findById(req.getCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("Company", req.getCompanyId()));
        requireSameOrg(company.getPidOrg(), orgId, "Company");
        if (!"Y".equals(company.getStatus())) {
            throw new InactiveEntityException("Company", company.getCompanyName());
        }

        PosProductCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", req.getCategoryId()));
        requireSameOrg(category.getPidOrg(), orgId, "Category");
        if (!"Y".equals(category.getStatus())) {
            throw new InactiveEntityException("Category", category.getCategoryName());
        }

        PosProductSubCategory subCategory = null;
        if (req.getSubCategoryId() != null) {
            subCategory = subCategoryRepo.findById(req.getSubCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sub-Category", req.getSubCategoryId()));
            if (!"Y".equals(subCategory.getStatus())) {
                throw new InactiveEntityException("Sub-Category", subCategory.getSubCategoryName());
            }
            // A sub-category must belong to the chosen category, otherwise the
            // product ends up filed under a pair that the UI can never reproduce.
            if (subCategory.getPidCategory() != null
                    && !subCategory.getPidCategory().equals(req.getCategoryId())) {
                throw new BusinessException(
                        "The selected sub-category does not belong to the selected category.",
                        HttpStatus.BAD_REQUEST, "SUBCATEGORY_CATEGORY_MISMATCH");
            }
        }

        PosProductUnit unit = unitRepo.findById(req.getUnitId())
                .orElseThrow(() -> new ResourceNotFoundException("Unit", req.getUnitId()));
        requireSameOrg(unit.getPidOrg(), orgId, "Unit");
        if (!"Y".equals(unit.getStatus())) {
            throw new InactiveEntityException("Unit", unit.getUnitName());
        }

        PosBoxInfo box = null;
        if (req.getBoxId() != null) {
            box = boxRepo.findById(req.getBoxId())
                    .orElseThrow(() -> new ResourceNotFoundException("Box", req.getBoxId()));
            requireSameOrg(box.getPidOrg(), orgId, "Box");
            if (!"Y".equals(box.getStatus())) {
                throw new InactiveEntityException("Box", box.getBoxSizeTitle());
            }
        }

        return new ValidatedReferences(generic, company, category, subCategory, unit, box);
    }

    private void requireSameOrg(Long entityOrgId, Long orgId, String label) {
        // Null org on a master row means "shared across the install" — allowed.
        if (entityOrgId != null && !entityOrgId.equals(orgId)) {
            throw new ResourceNotFoundException(label + " is not available for your organization.");
        }
    }

    /* ── Uniqueness ──────────────────────────────────────────────────────── */

    private void validateUniqueness(ProductMasterRequest req, Long orgId, long excludeId) {
        /* [V72] SKU is not checked for uniqueness, by decision.
         *
         * It is optional and free-text — a shelf or supplier reference rather
         * than an identity. Two products may legitimately carry the same one,
         * and the guard was refusing entries the business considers valid. The
         * identifier that is unique and that the system actually relies on is
         * PRODUCT_CODE, which the server generates and no client can supply.
         * The unique index was dropped in the same change, so this is not a
         * check moved from the service to the database — it is gone from both.
         */

        String barcode = trim(req.getBarcode());
        if (barcode != null && !barcode.isEmpty()
                && productRepo.existsByBarcodeIgnoreCase(orgId, barcode, excludeId)) {
            throw new BusinessException("Barcode '" + barcode + "' already exists.",
                    HttpStatus.CONFLICT, "PRODUCT_BARCODE_EXISTS");
        }

        /* The product name, on its own, within this pharmacy.
         *
         * This is stricter than what was here before, and deliberately so. The
         * old guard was the business key — name + company + generic — which let
         * "Napa 500mg" be entered twice as long as the second one named a
         * different manufacturer. On one pharmacy's own list that is a double
         * entry whatever the maker says, and it is the one a person notices,
         * because the two rows are indistinguishable in every picker.
         *
         * Scoped to the branch: two shops in a group each stocking Ace 500mg are
         * not duplicating anything. Nothing in this database breaks the rule
         * today — the duplicates that exist are all across branches.
         */
        String name = trim(req.getProductName());
        if (name != null && !name.isEmpty()
                && productRepo.existsByProductNameInBranch(
                        orgId, SecurityUtils.getCurrentBranchId(), name, excludeId)) {
            throw new BusinessException(
                    "A product named \"" + name + "\" is already on your list. "
                    + "Open that product to change it, or give this one a name that tells "
                    + "the two apart.",
                    HttpStatus.CONFLICT, "PRODUCT_NAME_EXISTS");
        }

        // The older, narrower guard is kept below it. It now only fires where
        // the name check cannot — it never will while the name rule stands, but
        // removing a duplicate guard is not something to do as a side effect of
        // adding one.
        // With no generic the key is name + company. Passing a null generic to the
        // three-part query would compare it with =, which is never true in SQL —
        // the duplicate guard would silently stop guarding for general products
        // and let the same item be created twice.
        boolean duplicate = req.getGenericId() == null
                ? productRepo.existsByBusinessKeyNoGeneric(orgId, trim(req.getProductName()),
                        req.getCompanyId(), excludeId)
                : productRepo.existsByBusinessKey(orgId, trim(req.getProductName()),
                        req.getCompanyId(), req.getGenericId(), excludeId);
        if (duplicate) {
            throw new BusinessException(
                    req.getGenericId() == null
                        ? "A product with this name already exists for the selected company."
                        : "A product with this name already exists for the selected company and generic.",
                    HttpStatus.CONFLICT, "PRODUCT_DUPLICATE");
        }
    }

    /* ── Stock ladder ────────────────────────────────────────────────────── */

    /**
     * MINIMUM_STOCK ≤ SAFETY_STOCK ≤ REORDER_LEVEL ≤ MAXIMUM_STOCK.
     *
     * <p>Unset levels default to zero, so a product with no stock configuration
     * satisfies the ladder trivially. MAXIMUM_STOCK of zero is read as "no
     * ceiling configured" rather than "ceiling of zero" — otherwise every
     * product would have to declare a maximum before it could declare a reorder
     * level. The DB check constraint ck_product_stock_ladder encodes the same rule.
     */
    private void validateStockLadder(ProductMasterRequest req) {
        BigDecimal min     = nvl(req.getMinimumStock());
        BigDecimal safety  = nvl(req.getSafetyStock());
        BigDecimal reorder = nvl(req.getReorderLevel());
        BigDecimal max     = nvl(req.getMaximumStock());

        if (min.compareTo(safety) > 0) {
            throw fieldError("minimumStock",
                    "Minimum stock cannot be greater than safety stock.");
        }
        if (safety.compareTo(reorder) > 0) {
            throw fieldError("safetyStock",
                    "Safety stock cannot be greater than the reorder level.");
        }
        if (max.compareTo(BigDecimal.ZERO) > 0 && reorder.compareTo(max) > 0) {
            throw fieldError("reorderLevel",
                    "Reorder level cannot be greater than maximum stock.");
        }
    }

    /* ── Pricing inputs ──────────────────────────────────────────────────── */

    /**
     * Guards the inputs to the price chain. A conversion rate of exactly 100
     * drives TP_PRICE to zero, which then zeroes IGTA and BUY_PRICE — almost
     * always a typo rather than an intent, so it is worth rejecting explicitly
     * instead of silently storing a free product.
     */
    private void validatePricingInputs(ProductMasterRequest req) {
        BigDecimal rate = req.getConversionRate();
        if (rate != null && rate.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw fieldError("conversionRate",
                    "Conversion rate of 100% or more would reduce the trade price to zero.");
        }
    }

    private BusinessException fieldError(String field, String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST,
                "VALIDATION_" + field.toUpperCase());
    }

    private static BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static String trim(String v)        { return v != null ? v.trim() : null; }

    /**
     * Master rows resolved during validation, handed to the service so it does
     * not have to re-query for the denormalised names it stores and returns.
     */
    public record ValidatedReferences(
            PosGenericInfo        generic,
            PosCompanyInfo        company,
            PosProductCategory    category,
            PosProductSubCategory subCategory,
            PosProductUnit        unit,
            PosBoxInfo            box
    ) {}
}
