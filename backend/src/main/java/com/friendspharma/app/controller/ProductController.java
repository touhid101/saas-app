package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.ProductBulkRequest;
import com.friendspharma.app.dto.request.ProductMasterRequest;
import com.friendspharma.app.dto.response.ProductBulkResultResponse;
import com.friendspharma.app.dto.response.ProductListItemResponse;
import com.friendspharma.app.dto.response.ProductMasterResponse;
import com.friendspharma.app.service.interfaces.ProductBulkService;
import com.friendspharma.app.service.interfaces.ProductService;
import com.friendspharma.app.specification.ProductSpecification;
import com.friendspharma.app.specification.ProductSpecification.ProductFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.friendspharma.app.entity.PosSupplierInfo;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.PosSupplierInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Product Master REST API.
 *
 * <p>Owns every {@code /api/products} route. The older product endpoints that
 * lived on MasterDataController were removed when this controller was added —
 * two controllers mapping the same path is an ambiguous-mapping startup failure,
 * and the duplicate would have bypassed the validation and pricing rules here.
 *
 * <p>Authorisation follows the roles already seeded in V3:
 * ROLE01 Super Admin, ROLE02 Branch Manager, ROLE05 Purchase Officer.
 * Read is open to any authenticated user because the POS, Sales and Inventory
 * screens all need product lookups; writes are restricted.
 *
 * <p>Note there is no orgId parameter anywhere. Tenant scope is resolved from
 * the JWT inside the service, closing the "?orgId=2 reads another tenant"
 * hole that the older master-data endpoints left open.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Product Master", description = "Pharmaceutical product master — CRUD, search, image, status")
public class ProductController {

    private static final int MAX_PAGE_SIZE = 200;

    /**
     * The manufacturer a supplier distributes for.
     *
     * <p>Refuses rather than falling back to the whole catalogue. A buyer who
     * filtered by supplier and was quietly shown everything would raise a
     * requisition that the supplier cannot fill, and would not find out until
     * somebody rang them.
     */
    private Long companyOfSupplier(Long supplierId) {
        PosSupplierInfo s = supplierRepo.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));
        if (s.getPosPidCompany() == null) {
            throw new ValidationException(
                    s.getSupplierName() + " has no manufacturer on file, so there is no way to tell "
                    + "which products they supply. Set one on the supplier first.");
        }
        return s.getPosPidCompany();
    }

    private final ProductService     productService;
    private final ProductBulkService productBulkService;
    private final PosSupplierInfoRepository supplierRepo;

    /* ══════════════════════════════════════════════════════════════════════
       LIST / SEARCH
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping
    @Operation(summary = "Search products",
               description = "Server-side pagination, filtering and sorting. Filters combine with AND. "
                           + "supplierId narrows the list to what that supplier can actually be asked "
                           + "for — they distribute one manufacturer, so it resolves to that "
                           + "manufacturer's products. It is resolved on the server: a client cannot "
                           + "ask for one supplier's catalogue and be handed another's.")
    public ResponseEntity<ApiResponse<PageResponse<ProductListItemResponse>>> list(
            @RequestParam(required = false) String  search,
            @RequestParam(required = false) String  sku,
            @RequestParam(required = false) String  barcode,
            @RequestParam(required = false) Long    genericId,
            @RequestParam(required = false) Long    companyId,
            @RequestParam(required = false) Long    supplierId,
            @RequestParam(required = false) Long    categoryId,
            @RequestParam(required = false) Long    unitId,
            @RequestParam(required = false) String  status,
            @RequestParam(required = false) String  stockStatus,
            @RequestParam(required = false) Boolean batchTracking,
            @RequestParam(required = false) Boolean expiryTracking,
            @RequestParam(required = false) Boolean prescriptionRequired,
            @RequestParam(defaultValue = "0")           int    page,
            @RequestParam(defaultValue = "25")          int    size,
            @RequestParam(defaultValue = "productName") String sortBy,
            @RequestParam(defaultValue = "asc")         String sortDir) {

        // A supplier is a manufacturer, as far as the catalogue is concerned.
        // Given both, the supplier wins: it is the more specific statement of
        // intent, and silently returning the union would produce a requisition
        // that cannot be placed with anybody.
        Long effectiveCompanyId = supplierId != null ? companyOfSupplier(supplierId) : companyId;

        ProductFilter filter = new ProductFilter(
                search, sku, barcode, genericId, effectiveCompanyId, categoryId, unitId,
                status, stockStatus, batchTracking, expiryTracking, prescriptionRequired);

        // Clamp the page size so a client cannot request the whole catalogue in
        // one response, and whitelist the sort column (see resolveSortField).
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, ProductSpecification.resolveSortField(sortBy));

        return ResponseEntity.ok(ApiResponse.ok(
                productService.search(filter, PageRequest.of(Math.max(page, 0), safeSize, sort))));
    }

    /* ══════════════════════════════════════════════════════════════════════
       LOOKUPS
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/lookups")
    @Operation(summary = "Dropdown data for the product form",
               description = "Active generics, companies, categories, sub-categories, units and boxes "
                           + "(each box carries its derived noOfPcs), plus the pricing defaults.")
    public ResponseEntity<ApiResponse<Map<String, List<?>>>> lookups() {
        return ResponseEntity.ok(ApiResponse.ok(productService.getFormLookups()));
    }

    /* ══════════════════════════════════════════════════════════════════════
       READ ONE
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/barcode/{barcode}")
    @Operation(summary = "Look up a product by barcode")
    public ResponseEntity<ApiResponse<ProductMasterResponse>> getByBarcode(@PathVariable String barcode) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getByBarcode(barcode)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one product")
    public ResponseEntity<ApiResponse<ProductMasterResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getById(id)));
    }

    /* ══════════════════════════════════════════════════════════════════════
       WRITE
       ══════════════════════════════════════════════════════════════════════ */

    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Create a product",
               description = "Product code is generated server-side. TP price, IGTA and buy price are "
                           + "calculated server-side and cannot be supplied by the client.")
    public ResponseEntity<ApiResponse<ProductMasterResponse>> create(
            @Valid @RequestBody ProductMasterRequest request) {
        ProductMasterResponse created = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Product created successfully.", created));
    }

    /**
     * Saves an edit as a new version.
     *
     * <p><b>The id in the reply is not the id in the path.</b> The product named
     * by {@code id} is retired and the changes are written to a new row, so a
     * client that keeps using the id it sent will be editing a superseded
     * version and get a 409 next time. Read the id back from the response.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Update a product — writes a new version",
               description = "The product given is marked INACTIVE and a new ACTIVE version is "
                           + "created with the changes. Transactions and stock movements keep "
                           + "pointing at the old version; stock, open carts and discount rules "
                           + "move to the new one. The response carries the NEW id. Product code "
                           + "is carried over unchanged; derived prices are recalculated.")
    public ResponseEntity<ApiResponse<ProductMasterResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductMasterRequest request) {
        ProductMasterResponse next = productService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(
                "Saved as version " + next.getVersionNo() + ". The previous version was retired "
                + "and still holds its history.", next));
    }

    /**
     * Is this product name still free? Asked by the form as the cursor leaves
     * the field, so a clash is reported there and then rather than at Save,
     * after everything else has been filled in.
     */
    @GetMapping("/name-available")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Check a product name before saving",
               description = "Trimmed and case-insensitive, scoped to the caller's branch. "
                           + "Pass excludeId when editing so a product does not clash with itself.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nameAvailable(
            @RequestParam String name,
            @RequestParam(required = false) Long excludeId) {

        boolean free = productService.isProductNameAvailable(name, excludeId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("name", name == null ? "" : name.trim());
        out.put("available", free);
        out.put("message", free ? null
                : "A product named \"" + (name == null ? "" : name.trim())
                  + "\" is already on your list.");
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Every version of this product, newest first",
               description = "Answers the same list whichever version's id is given.")
    public ResponseEntity<ApiResponse<java.util.List<ProductMasterResponse>>> versions(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.versionHistory(id)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Activate or deactivate a product")
    public ResponseEntity<ApiResponse<ProductMasterResponse>> changeStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String status = body.get("status");
        ProductMasterResponse updated = productService.changeStatus(id, status);
        String message = "INACTIVE".equalsIgnoreCase(status)
                ? "Product deactivated successfully."
                : "Product activated successfully.";
        return ResponseEntity.ok(ApiResponse.ok(message, updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Delete a product",
               description = "Returns 409 PRODUCT_HAS_TRANSACTIONS when transaction history exists; "
                           + "deactivate the product instead.")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.ok(ApiResponse.noContent("Product deleted successfully."));
    }

    /* ══════════════════════════════════════════════════════════════════════
       BULK IMPORT
       ══════════════════════════════════════════════════════════════════════ */

    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Bulk import products from the Excel template",
               description = "The spreadsheet is parsed in the browser and posted as rows. Every row is "
                           + "validated and saved through the same service the single-product form uses, "
                           + "so prices are still calculated server-side and codes still generated. "
                           + "Rows are independent: a failure reports against that row only. "
                           + "mode=CREATE fails rows whose SKU exists; mode=UPSERT updates them.")
    public ResponseEntity<ApiResponse<ProductBulkResultResponse>> bulkImport(
            @Valid @RequestBody ProductBulkRequest request) {

        ProductBulkResultResponse result = productBulkService.importRows(request);

        String message = result.getFailed() == 0
                ? String.format("Import complete — %d created, %d updated.",
                        result.getCreated(), result.getUpdated())
                : String.format("Import finished with %d error%s — %d created, %d updated.",
                        result.getFailed(), result.getFailed() == 1 ? "" : "s",
                        result.getCreated(), result.getUpdated());

        return ResponseEntity.ok(ApiResponse.ok(message, result));
    }

    /* ══════════════════════════════════════════════════════════════════════
       IMAGE
       ══════════════════════════════════════════════════════════════════════ */

    @PostMapping(path = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Upload or replace the product image",
               description = "Accepts JPG, PNG, WEBP, GIF. The file is validated by MIME type, extension "
                           + "and magic bytes, then stored under a generated name.")
    public ResponseEntity<ApiResponse<ProductMasterResponse>> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(
                ApiResponse.ok("Product image updated successfully.", productService.uploadImage(id, file)));
    }

    @DeleteMapping("/{id}/image")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Remove the product image")
    public ResponseEntity<ApiResponse<ProductMasterResponse>> deleteImage(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.ok("Product image removed.", productService.deleteImage(id)));
    }
}
