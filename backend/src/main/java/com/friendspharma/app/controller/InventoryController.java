package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.response.StockResponses;
import com.friendspharma.app.dto.response.StockResponses.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Branch-wise inventory reporting.
 *
 * <p><b>Branch comes from the session, not from the request.</b> The previous
 * version took {@code branchId} as a query parameter, so any user could read any
 * branch's stock by changing a number in the URL. It is now resolved from the
 * JWT, exactly as org and company already are.
 *
 * <p>An administrator ({@code ROLE01}) may still ask for another branch, for a
 * head-office consolidated view. Anyone else who tries gets <b>404, not 403</b>
 * — consistent with the cross-tenant rule in Product Master, and because a 403
 * confirms the branch exists.
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory",
     description = "Branch-wise batch stock: ledger, batch stock, expiry, alerts, movements, FEFO preview")
public class InventoryController {

    private static final int MAX_PAGE_SIZE = 200;

    private final InventoryService svc;
    private final BranchScopeService branchScope;

    /* ══════════════════════════════════════════════════════════════════════
       DASHBOARD
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/dashboard")
    @Operation(summary = "Stock headline figures for the caller's branch")
    public ResponseEntity<ApiResponse<StockDashboard>> dashboard(
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getDashboard(resolveBranch(branchId))));
    }

    /* ══════════════════════════════════════════════════════════════════════
       STOCK
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/stock")
    @Operation(summary = "Per-product stock rollup",
               description = "Quantities in pieces, with box and strip equivalents derived from the "
                           + "packing frozen on each batch.")
    public ResponseEntity<ApiResponse<PageResponse<ProductStockRow>>> stockLedger(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) Long   categoryId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) Long   branchId,
            /** Sales pickers pass true — they may only offer what can be sold. */
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getStockLedger(
                resolveBranch(branchId),
                filter(search, companyId, batchNo, categoryId, productId, inStockOnly),
                paged(page, size))));
    }

    @GetMapping("/batches")
    @Operation(summary = "Lot-level stock",
               description = "One row per batch. Two deliveries sharing an expiry stay separate rows "
                           + "with their own costs.")
    public ResponseEntity<ApiResponse<PageResponse<BatchStockRow>>> batchStock(
            @RequestParam(required = false) String  search,
            @RequestParam(required = false) Long    companyId,
            @RequestParam(required = false) String  batchNo,
            @RequestParam(required = false) Long    categoryId,
            @RequestParam(required = false) Long    productId,
            @RequestParam(required = false) Long    branchId,
            @RequestParam(defaultValue = "false")   boolean includeEmpty,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getBatchStock(
                resolveBranch(branchId),
                filter(search, companyId, batchNo, categoryId, productId),
                includeEmpty, paged(page, size))));
    }

    @GetMapping("/lookups")
    @Operation(summary = "Filter dropdowns",
               description = "Companies and batch references that actually hold stock in this branch. "
                           + "Offering a manufacturer whose every lot is exhausted just produces an "
                           + "empty report and a puzzled user.")
    public ResponseEntity<ApiResponse<Map<String, List<?>>>> lookups(
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getFilterLookups(resolveBranch(branchId))));
    }

    /* ══════════════════════════════════════════════════════════════════════
       ALERTS
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/expiry")
    @Operation(summary = "Lots at or past expiry",
               description = "Already-expired lots are included — they are the more urgent case.")
    public ResponseEntity<ApiResponse<PageResponse<BatchStockRow>>> expiry(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) Long   categoryId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) Long   branchId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getExpiringBatches(
                resolveBranch(branchId),
                filter(search, companyId, batchNo, categoryId, productId),
                days, paged(page, size))));
    }

    @GetMapping("/alerts/slow-moving")
    @Operation(summary = "Lots with stock that have not sold in a while",
               description = "Stock on the shelf whose product has had no sale in this branch for the given "
                           + "number of days (default 90), most value first. Capital tied up and the next "
                           + "expiry write-off.")
    public ResponseEntity<ApiResponse<List<StockResponses.SlowMovingRow>>> slowMoving(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) Long   branchId,
            @RequestParam(defaultValue = "90") int days) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getSlowMoving(
                resolveBranch(branchId), filter(search, companyId, null, null, null), days)));
    }

    @GetMapping("/alerts/low-stock")
    @Operation(summary = "Products at or below their alert level in this branch")
    public ResponseEntity<ApiResponse<List<ProductStockRow>>> lowStock(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) Long   branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getLowStockAlerts(
                resolveBranch(branchId), filter(search, companyId, null, null, null))));
    }

    @GetMapping("/alerts/out-of-stock")
    @Operation(summary = "Products with no sellable stock in this branch",
               description = "A product whose only lots have expired counts as out of stock.")
    public ResponseEntity<ApiResponse<List<ProductStockRow>>> outOfStock(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) Long   branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getOutOfStockAlerts(
                resolveBranch(branchId), filter(search, companyId, null, null, null))));
    }

    /* ══════════════════════════════════════════════════════════════════════
       MOVEMENTS
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/movements")
    @Operation(summary = "Stock movement ledger",
               description = "Every piece that entered or left a lot, and the document that caused it.")
    public ResponseEntity<ApiResponse<PageResponse<MovementRow>>> movements(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) Long   categoryId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long   branchId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getMovementLedger(
                resolveBranch(branchId),
                filter(search, companyId, batchNo, categoryId, productId),
                fromDate, toDate, paged(page, size))));
    }

    @GetMapping("/movement-summary")
    @Operation(summary = "Product movement over a period",
               description = "Opening, purchased, purchase-returned, sold, sales-returned, adjusted and "
                           + "closing, per product — all four document types in one report. Each row "
                           + "carries the product's sale prices for every unit and customer tier, and "
                           + "salesCostValue is the COGS half of actual profit, taken from the cost of "
                           + "the batches actually consumed.")
    public ResponseEntity<ApiResponse<MovementSummary>> movementSummary(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) Long   categoryId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getMovementSummary(
                resolveBranch(branchId),
                filter(search, companyId, batchNo, categoryId, productId),
                fromDate, toDate)));
    }

    @GetMapping("/profit")
    @Operation(summary = "Actual profit per product",
               description = "Revenue as invoiced against the cost of the specific batches consumed, "
                           + "paired line by line through the movement log — not an average product "
                           + "cost. Sales returns are netted off both sides. Margin is stated against "
                           + "revenue, not cost.")
    public ResponseEntity<ApiResponse<ProfitSummary>> profit(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   companyId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) Long   categoryId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getProfit(
                resolveBranch(branchId),
                filter(search, companyId, batchNo, categoryId, productId),
                fromDate, toDate)));
    }

    /**
     * Assembles the shared filter, trimming blanks to null.
     *
     * <p>An empty string arrives from a cleared input box and must mean "no
     * filter" — passed through as-is it becomes a LIKE on {@code %%}, which
     * matches everything except rows where the column is null.
     */
    private static InventoryService.StockFilter filter(String search, Long companyId, String batchNo,
                                                       Long categoryId, Long productId) {
        return filter(search, companyId, batchNo, categoryId, productId, false);
    }

    private static InventoryService.StockFilter filter(String search, Long companyId, String batchNo,
                                                       Long categoryId, Long productId,
                                                       boolean inStockOnly) {
        return new InventoryService.StockFilter(
                trimToNull(search), companyId, trimToNull(batchNo), categoryId, productId, inStockOnly);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /* ══════════════════════════════════════════════════════════════════════
       FEFO PREVIEW
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/fefo-preview")
    @Operation(summary = "Which batches a sale would consume",
               description = "Walks the lots in the same order the sale will, so the preview and the "
                           + "sale agree. Reads only.")
    public ResponseEntity<ApiResponse<StockAllocation>> fefoPreview(
            @RequestParam Long       productId,
            @RequestParam BigDecimal qty,
            @RequestParam(defaultValue = "PCS") String unit,
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(
                svc.previewAllocation(resolveBranch(branchId), productId, qty, unit)));
    }

    /* ══════════════════════════════════════════════════════════════════════
       RECONCILIATION
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/reconcile")
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Batches whose balance disagrees with their movement history",
               description = "Expected to be empty. Anything listed means a balance changed outside "
                           + "the stock service; the movement log is the side to trust.")
    public ResponseEntity<ApiResponse<List<ReconciliationRow>>> reconcile() {
        List<ReconciliationRow> rows = svc.reconcile();
        String message = rows.isEmpty()
                ? "All batch balances agree with their movement history."
                : rows.size() + " batch(es) diverge from their movement history and need investigation.";
        return ResponseEntity.ok(ApiResponse.ok(message, rows));
    }

    /* ══════════════════════════════════════════════════════════════════════
       HELPERS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The branch to report on.
     *
     * <p>Session branch by default. An explicit {@code branchId} is honoured
     * only for {@code ROLE01}; for anyone else it is treated as a request for a
     * branch that, as far as they are concerned, does not exist.
     */
    /**
     * <p>The rule this used to hold now lives in {@link BranchScopeService},
     * because every other screen needed the same answer and each was quietly
     * giving a different one — most of them "no branch at all".
     */
    private Long resolveBranch(Long requestedBranchId) {
        return branchScope.resolve(requestedBranchId);
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
