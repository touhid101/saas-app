package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.dto.response.ReportGridResponse;
import com.friendspharma.app.service.impl.ReportGridServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Reports, as grids.
 *
 * <p>One endpoint serves every report: the response describes its own columns,
 * so the screen renders any of them without knowing what it is looking at, and
 * a new report needs no frontend change.
 *
 * <p>Separate from the older {@code /api/v1/reports}, which is left in place
 * rather than deleted so nothing that still points at it breaks — though all 28
 * of its endpoints currently return 500, having been written against a schema
 * with {@code INVOICE_DATE}, {@code PID_CUSTOMER} and {@code GRAND_TOTAL}
 * columns that this database does not have.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Sales, purchase, finance and collection reporting")
public class ReportGridController {

    private final ReportGridServiceImpl svc;
    private final BranchScopeService branchScope;

    @GetMapping("/catalogue")
    @Operation(summary = "The reports available here",
               description = "Inventory reports are not listed: they live under /api/inventory "
                           + "against the stock ledger, and duplicating them would mean two sets "
                           + "of numbers to reconcile.")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> catalogue() {
        return ResponseEntity.ok(ApiResponse.ok(
                ReportGridServiceImpl.CATALOGUE.entrySet().stream()
                        .map(e -> Map.of("id", e.getKey(), "title", e.getValue()))
                        .toList()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Run a report",
               description = "Every filter is optional. The response says which ones the report "
                           + "actually honours, so the screen can hide the rest rather than offer "
                           + "a control that does nothing.")
    public ResponseEntity<ApiResponse<ReportGridResponse>> run(
            @PathVariable String id,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long costCenterId) {

        var filters = new ReportGridServiceImpl.Filters(
                branchScope.resolve(branchId), companyId, categoryId, customerId, supplierId,
                from, to, q, paymentStatus, employeeId, status, costCenterId);
        return ResponseEntity.ok(ApiResponse.ok(svc.run(id, filters)));
    }
}
