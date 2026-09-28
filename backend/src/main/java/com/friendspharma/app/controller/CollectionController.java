package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.CollectionRequest;
import com.friendspharma.app.dto.response.CollectionResponse;
import com.friendspharma.app.service.interfaces.CollectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
@Tag(name = "Collection", description = "Record & track payments against sales invoices")
public class CollectionController {

    private final CollectionService collectionService;
    private final BranchScopeService branchScope;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a payment collection against a sales invoice")
    public ApiResponse<CollectionResponse.CollectionDto> record(
            @Valid @RequestBody CollectionRequest.RecordCollectionRequest req) {
        return ApiResponse.success(collectionService.record(req), "Collection recorded");
    }

    @PutMapping("/{pidCollection}")
    @Operation(summary = "Update a collection record")
    public ApiResponse<CollectionResponse.CollectionDto> update(
            @PathVariable Long pidCollection,
            @Valid @RequestBody CollectionRequest.UpdateCollectionRequest req) {
        return ApiResponse.success(collectionService.update(pidCollection, req));
    }

    /* Authorisation is the Delete right on Collection (MOD10), checked in the
       service. The @PreAuthorize that used to sit here named MANAGER, an
       authority nothing grants, and ADMIN, which only Super Admin holds — so it
       refused the Accounts staff it was written for. */
    @DeleteMapping("/{pidCollection}")
    @Operation(summary = "Cancel a collection (reversible by creating a new one)")
    public ApiResponse<Void> cancel(
            @PathVariable Long pidCollection,
            @RequestParam(required = false, defaultValue = "Cancelled by user") String reason) {
        collectionService.cancel(pidCollection, reason);
        return ApiResponse.success(null, "Collection cancelled");
    }

    @GetMapping("/{pidCollection}")
    @Operation(summary = "Get a single collection record")
    public ApiResponse<CollectionResponse.CollectionDto> getById(@PathVariable Long pidCollection) {
        return ApiResponse.success(collectionService.getById(pidCollection));
    }

    @GetMapping("/transaction/{transactionId}")
    @Operation(summary = "All collections for a specific invoice")
    public ApiResponse<List<CollectionResponse.CollectionDto>> byTransaction(
            @PathVariable Long transactionId) {
        return ApiResponse.success(collectionService.getByTransaction(transactionId));
    }

    @GetMapping("/transaction/{transactionId}/summary")
    @Operation(summary = "Collection summary (collected vs outstanding) for an invoice")
    public ApiResponse<CollectionResponse.CollectionSummaryDto> summary(
            @PathVariable Long transactionId) {
        return ApiResponse.success(collectionService.getSummary(transactionId));
    }

    @GetMapping("/branch")
    @Operation(summary = "Paginated collections for a branch within a date range")
    public ApiResponse<PageResponse<CollectionResponse.CollectionDto>> byBranch(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        var pg = collectionService.listByBranch(orgId, branchScope.resolve(branchId), from, to,
                PageRequest.of(page, size, Sort.by("collectionDate").descending()));
        return ApiResponse.success(PageResponse.of(pg));
    }

    @GetMapping("/delivery-man/{deliveryManId}")
    @Operation(summary = "Collections assigned to a delivery man")
    public ApiResponse<PageResponse<CollectionResponse.CollectionDto>> byDeliveryMan(
            @PathVariable Long deliveryManId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        var pg = collectionService.listByDeliveryMan(deliveryManId, from, to,
                PageRequest.of(page, size, Sort.by("collectionDate").descending()));
        return ApiResponse.success(PageResponse.of(pg));
    }
}
