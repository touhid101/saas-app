package com.friendspharma.app.controller;

import org.springframework.web.multipart.MultipartFile;
import com.friendspharma.app.service.impl.ProductImageStorageService;
import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.entity.GlobalProduct;
import com.friendspharma.app.service.impl.GlobalProductServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Super Admin's medicine catalogue, and pushing it into pharmacies.
 *
 * <p>Super Admin only. A pharmacy never reads this table — it reads its own
 * products, which are copies. That separation is what lets a Super Admin correct
 * a spelling here without repricing three hundred shelves.
 */
@RestController
@RequestMapping("/api/admin/global-products")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE01')")
@Tag(name = "Global product catalogue", description = "The shared medicine list — Super Admin only")
public class GlobalProductController {

    private final GlobalProductServiceImpl svc;
    private final ProductImageStorageService imageStorage;

    /**
     * Sets the picture on a catalogue entry.
     *
     * <p>Reuses the product image store, so a catalogue picture is validated the
     * same way a pharmacy's own upload is — whitelisted extension, matching MIME
     * type and magic bytes, generated filename.
     *
     * <p>Every pharmacy that imports this medicine afterwards gets its own copy of
     * the file. Pharmacies that imported it earlier keep whatever they had; the
     * catalogue does not reach onto a shelf it has already stocked, here or
     * anywhere else.
     */
    @PostMapping("/{id}/image")
    @Operation(summary = "Set the catalogue picture for a medicine")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok("Picture updated.", toRow(svc.setImage(id, file))));
    }

    @DeleteMapping("/{id}/image")
    @Operation(summary = "Remove the catalogue picture")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeImage(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Picture removed.", toRow(svc.clearImage(id))));
    }

    @GetMapping
    @Operation(summary = "Search the catalogue")
    public ResponseEntity<ApiResponse<PageResponse<Map<String, Object>>>> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String company,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        var found = svc.search(q, company, PageRequest.of(page, Math.min(size, 200)));
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(found.map(this::toRow))));
    }

    @GetMapping("/companies")
    @Operation(summary = "The manufacturers in the catalogue, for the filter")
    public ResponseEntity<ApiResponse<List<String>>> companies() {
        return ResponseEntity.ok(ApiResponse.ok(svc.companies()));
    }

    @PostMapping
    @Operation(summary = "Add a medicine to the catalogue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody GlobalProduct body) {
        body.setPidGlobalProduct(null);
        return ResponseEntity.ok(ApiResponse.ok("Added to the catalogue.", toRow(svc.save(body))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a catalogue entry",
               description = "Changes nothing on any pharmacy's shelf. Pharmacies hold copies.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id, @RequestBody GlobalProduct body) {
        body.setPidGlobalProduct(id);
        return ResponseEntity.ok(ApiResponse.ok("Catalogue updated.", toRow(svc.save(body))));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Retire a catalogue entry",
               description = "Deactivated rather than deleted: pharmacies that already imported "
                           + "it hold products pointing back at this row.")
    public ResponseEntity<ApiResponse<Void>> retire(@PathVariable Long id) {
        svc.retire(id);
        return ResponseEntity.ok(ApiResponse.noContent("Retired from the catalogue."));
    }

    /**
     * Copies catalogue entries into one or more pharmacies.
     *
     * <p>An empty {@code productIds} means the whole catalogue. Anything a branch
     * already holds is skipped rather than duplicated, so running this twice is
     * safe and the response says how much of it did nothing.
     */
    @PostMapping("/import")
    @Operation(summary = "Push catalogue products into pharmacies")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> importInto(
            @RequestBody Map<String, Object> body) {

        List<Long> branchIds = longs(body.get("branchIds"));
        List<Long> productIds = longs(body.get("productIds"));
        if (branchIds.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "Choose at least one pharmacy to import into.", "NO_BRANCH_CHOSEN"));
        }

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (Long branchId : branchIds) {
            var r = svc.importInto(branchId, productIds);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("branchId", r.branchId());
            m.put("branchName", r.branchName());
            m.put("imported", r.imported());
            m.put("alreadyHeld", r.alreadyHeld());
            results.add(m);
        }

        int total = results.stream().mapToInt(m -> (int) m.get("imported")).sum();
        return ResponseEntity.ok(ApiResponse.ok(
                total == 0 ? "Nothing new to import — those pharmacies already hold these products."
                           : total + " product(s) imported.", results));
    }

    private Map<String, Object> toRow(GlobalProduct g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getPidGlobalProduct());
        m.put("productName", g.getProductName());
        m.put("genericName", g.getGenericName());
        m.put("productType", g.getProductType());
        m.put("companyName", g.getCompanyName());
        m.put("categoryName", g.getCategoryName());
        m.put("subCategoryName", g.getSubCategoryName());
        // The ids as well as the names: the list shows the name, the edit dialog
        // needs the id to pre-select the right entry in each dropdown.
        m.put("pidGlobalCompany", g.getPidGlobalCompany());
        m.put("pidGlobalGeneric", g.getPidGlobalGeneric());
        m.put("pidGlobalCategory", g.getPidGlobalCategory());
        m.put("pidGlobalSubCategory", g.getPidGlobalSubCategory());
        m.put("pidGlobalUnit", g.getPidGlobalUnit());
        m.put("pidGlobalLeaf", g.getPidGlobalLeaf());
        m.put("pidGlobalBox", g.getPidGlobalBox());
        m.put("strength", g.getStrength());
        m.put("suggestedMrp", g.getSuggestedMrp());
        m.put("suggestedTp", g.getSuggestedTp());
        m.put("igta", g.getIgta());
        m.put("buyPrice", g.getBuyPrice());
        m.put("unitName", g.getUnitName());
        m.put("productCode", g.getProductCode());
        m.put("barcode", g.getBarcode());
        m.put("prescriptionFlag", g.getPrescriptionFlag());
        // Packaging, so the import can build the pharmacy's own leaf and box rows.
        m.put("pcsPerLeaf", g.getPcsPerLeaf());
        m.put("leafPerBox", g.getLeafPerBox());
        // The stored filename is internal; the screen needs a URL it can render.
        m.put("productImage", g.getProductImage());
        m.put("productImageUrl", imageStorage.toPublicUrl(g.getProductImage()));
        m.put("isActive", g.getIsActive());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> longs(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return ((List<Object>) list).stream()
                .filter(java.util.Objects::nonNull)
                .map(o -> Long.valueOf(String.valueOf(o)))
                .toList();
    }
}
