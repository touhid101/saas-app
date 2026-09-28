package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.SalesMergeResponse;
import com.friendspharma.app.service.impl.SalesMergeServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales-merges")
@RequiredArgsConstructor
@Tag(name = "Invoice Merge",
     description = "Combining a customer's open orders into one document for delivery and collection")
public class SalesMergeController {

    private final SalesMergeServiceImpl svc;

    @GetMapping("/eligible")
    @Operation(summary = "Orders that can still be merged",
               description = "Today's orders for this customer that are still at Submitted and are "
                           + "not already part of a merge.")
    public ResponseEntity<ApiResponse<List<SalesMergeResponse.Candidate>>> eligible(
            @RequestParam Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.eligible(customerId)));
    }

    @GetMapping
    @Operation(summary = "Merges raised")
    public ResponseEntity<ApiResponse<List<SalesMergeResponse.Merge>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now();
        // Defaults to today, which is the only day whose orders can be merged
        // anyway — a wider default would mostly return an empty list.
        return ResponseEntity.ok(ApiResponse.ok(
                svc.list(from != null ? from : today, to != null ? to : today)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SalesMergeResponse.Merge>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.detail(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03','ROLE09')")
    @Operation(summary = "Merge a customer's orders",
               description = "Creates one grouping invoice carrying the combined total. The source "
                           + "orders keep their lines, their stock and their money, and become "
                           + "read-only; the merge is what gets delivered and collected.")
    public ResponseEntity<ApiResponse<SalesMergeResponse.Merge>> merge(
            @RequestBody Map<String, Object> body) {
        Long customerId = body.get("customerId") == null ? null
                : Long.valueOf(String.valueOf(body.get("customerId")));
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) body.getOrDefault("orderIds", List.of());
        List<Long> ids = raw.stream().map(o -> Long.valueOf(String.valueOf(o))).toList();
        String remarks = body.get("remarks") == null ? null : String.valueOf(body.get("remarks"));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(svc.merge(customerId, ids, remarks)));
    }

    @PostMapping("/{id}/split")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE09')")
    @Operation(summary = "Split a merge back into its orders",
               description = "Allowed only while the merged document is still at Submitted.")
    public ResponseEntity<ApiResponse<Void>> split(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        svc.unmerge(id, body == null ? null : body.get("reason"));
        return ResponseEntity.ok(ApiResponse.ok("Merge split back into its orders.", null));
    }
}
