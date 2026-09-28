package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.entity.GlobalProduct;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.GlobalProductServiceImpl;
import com.friendspharma.app.service.impl.PermissionGuard;
import com.friendspharma.app.service.impl.ProductImageStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The medicine catalogue, as a pharmacy sees it.
 *
 * <p>The same catalogue {@link GlobalProductController} maintains, with two
 * differences that are the whole point of the screen:
 *
 * <ul>
 *   <li><b>Read-only.</b> No add, no edit, no retire. A pharmacy takes copies;
 *       the catalogue itself is maintained centrally, and a pharmacy correcting
 *       a spelling here would change it for everybody.</li>
 *   <li><b>One target, and it is not chosen.</b> The operator's screen picks
 *       which pharmacies receive an entry. This one imports into the caller's
 *       own branch, resolved from the session — a branch id in the request body
 *       is a branch id somebody can change.</li>
 * </ul>
 *
 * <p>Separate from the admin controller rather than a relaxed version of it.
 * That one is {@code @PreAuthorize("hasRole('ROLE01')")} at class level, and
 * loosening it to let a pharmacy read would put the editing endpoints one
 * mistake away from a customer.
 */
@RestController
@RequestMapping("/api/catalogue")
@RequiredArgsConstructor
@Tag(name = "Medicine Catalogue (pharmacy)",
     description = "Browse the shared catalogue and copy entries into your own product list")
public class BranchCatalogueController {

    /** The screen. */
    private static final String MODULE = "MOD30";

    /** Where imported rows land, and therefore what the import really is. */
    private static final String PRODUCT_MASTER = "MOD17";

    private final GlobalProductServiceImpl svc;
    private final PermissionGuard permissions;
    private final BranchScopeService branchScope;
    private final AllBranchRepository branchRepo;
    private final ProductImageStorageService imageStorage;

    /* ── Browse ──────────────────────────────────────────────────────────── */

    @GetMapping
    @Operation(summary = "Search the catalogue",
               description = "The shared catalogue, read-only. Identical rows to the operator's "
                           + "screen; the difference is what can be done with them.")
    public ResponseEntity<ApiResponse<PageResponse<Map<String, Object>>>> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String company,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        requireView();
        var found = svc.search(q, company, PageRequest.of(page, Math.min(size, 200)));
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(found.map(this::toRow))));
    }

    @GetMapping("/companies")
    @Operation(summary = "Manufacturers in the catalogue, for the filter")
    public ResponseEntity<ApiResponse<List<String>>> companies() {
        requireView();
        return ResponseEntity.ok(ApiResponse.ok(svc.companies()));
    }

    /**
     * Which pharmacy an import would land in, and whether it may be run.
     *
     * <p>The screen shows the branch name rather than a picker, so it reads it
     * from here instead of trusting whatever the session happens to hold — the
     * name shown and the branch written must be the same answer.
     */
    @GetMapping("/target")
    @Operation(summary = "The branch an import would go to")
    public ResponseEntity<ApiResponse<Map<String, Object>>> target() {
        requireView();
        Long branchId = branchScope.resolve(null);
        String name = branchRepo.findById(branchId)
                .map(b -> b.getBranchName()).orElse("Your pharmacy");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branchId);
        out.put("branchName", name);
        out.put("canImport", permissions.has(PRODUCT_MASTER, PermissionGuard.Right.CREATE));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /* ── Import ──────────────────────────────────────────────────────────── */

    /**
     * Copies chosen entries into the caller's own product list.
     *
     * <p>An empty selection means everything the caller is looking at — every
     * entry matching the search and manufacturer they have set, across all of
     * the pages, not the fifty on screen. Somebody who ticked nothing and
     * pressed Import meant all of it.
     *
     * <p>The filter is taken from the body for that reason. Without it the
     * server took the whole catalogue whatever the screen said, so a page
     * narrowed to one manufacturer offered "Import 120 medicines" and copied
     * three thousand. The count on the button and the rows written are now the
     * same query.
     *
     * <p>Anything already held is skipped, so running it twice is safe and the
     * reply says how much of it did nothing.
     */
    @PostMapping("/import")
    @Operation(summary = "Copy catalogue entries into this pharmacy",
               description = "Imports into your own branch — no branch is accepted from the "
                           + "request. Needs the Create right on Product Master, because that "
                           + "is where the copies land.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importHere(
            @RequestBody(required = false) Map<String, Object> body) {

        requireView();
        if (!permissions.has(PRODUCT_MASTER, PermissionGuard.Right.CREATE)) {
            throw new BusinessException(
                    "You do not have permission to add products. Importing creates them in your "
                    + "product list, so it needs the Create right on Product Master in RBAC.",
                    HttpStatus.FORBIDDEN, "PRODUCT_CREATE_NOT_PERMITTED");
        }

        Long branchId = branchScope.resolve(null);
        List<Long> productIds = longs(body == null ? null : body.get("productIds"));
        String q       = str(body == null ? null : body.get("q"));
        String company = str(body == null ? null : body.get("company"));

        var r = svc.importInto(branchId, productIds, q, company);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", r.branchId());
        out.put("branchName", r.branchName());
        out.put("imported", r.imported());
        out.put("alreadyHeld", r.alreadyHeld());

        String message = r.imported() == 0
                ? "Nothing new — your pharmacy already holds all " + r.alreadyHeld() + " of these."
                : r.imported() + " product(s) added to your list"
                  + (r.alreadyHeld() > 0 ? ", " + r.alreadyHeld() + " already held." : ".");
        return ResponseEntity.ok(ApiResponse.ok(message, out));
    }

    /* ── plumbing ────────────────────────────────────────────────────────── */

    private void requireView() {
        if (!permissions.has(MODULE, PermissionGuard.Right.VIEW)) {
            throw new BusinessException(
                    "You do not have permission to view the medicine catalogue.",
                    HttpStatus.FORBIDDEN, "CATALOGUE_VIEW_NOT_PERMITTED");
        }
    }

    /** Blank and absent are the same thing to the search: they match everything. */
    private static String str(Object raw) {
        return raw == null ? "" : raw.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> longs(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(java.util.Objects::nonNull)
                .map(x -> Long.valueOf(x.toString()))
                .toList();
    }

    /**
     * One catalogue row.
     *
     * <p>Deliberately the same shape the operator's screen receives, so the two
     * pages render from one contract and a column added there does not have to
     * be added here separately.
     */
    private Map<String, Object> toRow(GlobalProduct g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getPidGlobalProduct());
        m.put("productName", g.getProductName());
        m.put("strength", g.getStrength());
        m.put("genericName", g.getGenericName());
        m.put("productType", g.getProductType());
        m.put("companyName", g.getCompanyName());
        m.put("categoryName", g.getCategoryName());
        m.put("subCategoryName", g.getSubCategoryName());
        m.put("unitName", g.getUnitName());
        m.put("suggestedMrp", g.getSuggestedMrp());
        m.put("suggestedTp", g.getSuggestedTp());
        m.put("igta", g.getIgta());
        m.put("buyPrice", g.getBuyPrice());
        m.put("productCode", g.getProductCode());
        m.put("barcode", g.getBarcode());
        m.put("prescriptionFlag", g.getPrescriptionFlag());
        m.put("productImageUrl", imageStorage.toPublicUrl(g.getProductImage()));
        m.put("pcsPerLeaf", g.getPcsPerLeaf());
        m.put("leafPerBox", g.getLeafPerBox());
        return m;
    }
}
