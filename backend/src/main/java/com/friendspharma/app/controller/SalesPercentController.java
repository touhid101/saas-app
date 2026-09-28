package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.SalesPercentRequest;
import com.friendspharma.app.dto.response.SalesPercentResponse;
import com.friendspharma.app.service.interfaces.SalesPercentService;
import com.friendspharma.app.service.interfaces.SalesPercentService.SalesPercentFilter;
import com.friendspharma.app.specification.ProductSpecification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sales% REST API — the per-unit percentages that derive a product's sale price
 * from its MRP.
 *
 * <p>Separate from {@code /api/v1/sales-discount}, which maintains per-line
 * discount <em>rules</em>. This endpoint maintains <em>pricing</em>:
 * {@code Unit Sales Price = MRP − (MRP × Sales% / 100)} for BOX, STRIP and PCS,
 * for regular and special customers.
 *
 * <p>No orgId parameter anywhere — tenant scope comes from the JWT.
 */
@RestController
@RequestMapping("/api/sales-percentages")
@RequiredArgsConstructor
@Tag(name = "Sales Percentage", description = "Per-unit sale pricing from MRP (Sales%)")
public class SalesPercentController {

    private static final int MAX_PAGE_SIZE = 200;

    private final SalesPercentService salesPercentService;

    @GetMapping
    @Operation(summary = "Pricing grid",
               description = "Server-side pagination, filtering and sorting. Each row carries the six "
                           + "percentages and the sale price each produces. Use configured=false for the "
                           + "\"needs pricing\" list.")
    public ResponseEntity<ApiResponse<PageResponse<SalesPercentResponse>>> list(
            @RequestParam(required = false) String  search,
            @RequestParam(required = false) Long    companyId,
            @RequestParam(required = false) Long    categoryId,
            @RequestParam(required = false) Long    genericId,
            @RequestParam(required = false) String  status,
            @RequestParam(required = false) Boolean configured,
            @RequestParam(required = false) Boolean belowCost,
            @RequestParam(defaultValue = "0")           int    page,
            @RequestParam(defaultValue = "25")          int    size,
            @RequestParam(defaultValue = "productName") String sortBy,
            @RequestParam(defaultValue = "asc")         String sortDir) {

        SalesPercentFilter filter = new SalesPercentFilter(
                search, companyId, categoryId, genericId, status, configured, belowCost);

        // Clamp the page size, and whitelist the sort column — the product
        // whitelist already covers every field this grid can sort on.
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Sort.Direction dir = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(dir, ProductSpecification.resolveSortField(sortBy));

        return ResponseEntity.ok(ApiResponse.ok(
                salesPercentService.search(filter, PageRequest.of(Math.max(page, 0), safeSize, sort))));
    }

    @GetMapping("/lookups")
    @Operation(summary = "Filter dropdowns for the pricing grid")
    public ResponseEntity<ApiResponse<Map<String, List<?>>>> lookups() {
        return ResponseEntity.ok(ApiResponse.ok(salesPercentService.getFilterLookups()));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "One product's Sales% and the prices they produce")
    public ResponseEntity<ApiResponse<SalesPercentResponse>> getOne(@PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.ok(salesPercentService.getByProductId(productId)));
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Set one product's Sales%",
               description = "Sale prices are derived server-side from MRP and cannot be supplied. "
                           + "Omitting a percentage leaves it unchanged; use the matching clear* flag "
                           + "to unset one, since null means \"not priced in this unit\".")
    public ResponseEntity<ApiResponse<SalesPercentResponse>> update(
            @PathVariable Long productId,
            @Valid @RequestBody SalesPercentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Sales percentages updated.", salesPercentService.update(productId, request)));
    }

    @PostMapping("/bulk/preview")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "How many products a scope would touch",
               description = "Counts the matches for a company/category/generic scope without changing "
                           + "anything, so the confirmation can state the blast radius first.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewScope(
            @Valid @RequestBody SalesPercentRequest.Scope scope) {
        long count = salesPercentService.countInScope(scope);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("matchCount", count)));
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Apply one set of Sales% to many products",
               description = "How pricing is actually rolled out — a manufacturer quotes a trade rate "
                           + "for its whole range. Target either an explicit productIds list or a scope "
                           + "(companyId / categoryId / genericId); a scope reaches every match, not just "
                           + "the page on screen. overwriteExisting=false (the default) fills only "
                           + "unpriced units, so a bulk apply cannot wipe hand-tuned prices.")
    public ResponseEntity<ApiResponse<SalesPercentResponse.BulkResult>> applyBulk(
            @Valid @RequestBody SalesPercentRequest.Bulk request) {

        SalesPercentResponse.BulkResult result = salesPercentService.applyBulk(request);
        String message = result.getFailed() == 0
                ? String.format("Applied to %d product%s (%d skipped).",
                        result.getUpdated(), result.getUpdated() == 1 ? "" : "s", result.getSkipped())
                : String.format("Applied to %d product%s — %d skipped, %d failed.",
                        result.getUpdated(), result.getUpdated() == 1 ? "" : "s",
                        result.getSkipped(), result.getFailed());

        return ResponseEntity.ok(ApiResponse.ok(message, result));
    }
}
