package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.EcomCustomerRequests;
import com.friendspharma.app.dto.response.EcomCustomerResponses;
import com.friendspharma.app.service.impl.ecom.EcomCustomerAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The E-Commerce Customers console (MOD55). Staff JWT; the service requires a
 * platform operator with the matrix right for every call, so there is no
 * {@code @PreAuthorize} role list to fall out of step with it.
 */
@RestController
@RequestMapping("/api/ecom/admin/customers")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Customers console", description = "Approve, reject, suspend online customers and set their pricing tier")
public class EcomCustomerAdminController {

    private final EcomCustomerAdminService admin;

    @Operation(summary = "Online customers, pending first; every filter applied in the query")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<EcomCustomerResponses.ConsoleRow>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(admin.list(status, type, q, page, size))));
    }

    @Operation(summary = "How many customers sit in each approval status")
    @GetMapping("/status-counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> statusCounts() {
        return ResponseEntity.ok(ApiResponse.ok(admin.statusCounts()));
    }

    @Operation(summary = "One customer: profile, sign-in state, addresses, tier history, pharmacy links, audit")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.ConsoleDetail>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(admin.detail(id)));
    }

    @Operation(summary = "Approve (or reinstate) a customer, granting a tier")
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.ConsoleRow>> approve(
            @PathVariable Long id, @RequestBody(required = false) EcomCustomerRequests.Decision req) {
        return ResponseEntity.ok(ApiResponse.ok("Customer approved", admin.approve(id, req)));
    }

    @Operation(summary = "Reject a pending registration (reason required)")
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.ConsoleRow>> reject(
            @PathVariable Long id, @RequestBody(required = false) EcomCustomerRequests.Decision req) {
        return ResponseEntity.ok(ApiResponse.ok("Registration rejected", admin.reject(id, req)));
    }

    @Operation(summary = "Suspend an approved customer (reason required); signs them out everywhere")
    @PostMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.ConsoleRow>> suspend(
            @PathVariable Long id, @RequestBody(required = false) EcomCustomerRequests.Decision req) {
        return ResponseEntity.ok(ApiResponse.ok("Customer suspended", admin.suspend(id, req)));
    }

    @Operation(summary = "Approve, correct or clear a wholesale customer's region",
               description = "Only a wholesale or special account has one. Clearing it stops wholesale "
                           + "ordering until a region is approved again, and the customer is told what to complete.")
    @PostMapping("/{id}/region")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.ConsoleRow>> setRegion(
            @PathVariable Long id, @RequestBody EcomCustomerRequests.SetRegion req) {
        return ResponseEntity.ok(ApiResponse.ok("Wholesale region saved", admin.setRegion(id, req)));
    }

    @Operation(summary = "Change the pricing tier — journaled and pushed to every pharmacy row")
    @PostMapping("/{id}/type")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.ConsoleRow>> setType(
            @PathVariable Long id, @Valid @RequestBody EcomCustomerRequests.SetType req) {
        return ResponseEntity.ok(ApiResponse.ok("Customer type changed", admin.setType(id, req)));
    }

    @Operation(summary = "Create or adopt the pharmacy customer row for this customer at one branch")
    @PostMapping("/{id}/branches/{branchId}/link")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.BranchLink>> linkBranch(
            @PathVariable Long id, @PathVariable Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok("Pharmacy linked", admin.linkBranch(id, branchId)));
    }

    @Operation(summary = "Clear a sign-in lockout")
    @PostMapping("/{id}/unlock")
    public ResponseEntity<ApiResponse<Void>> unlock(@PathVariable Long id) {
        admin.unlock(id);
        return ResponseEntity.ok(ApiResponse.noContent("Account unlocked"));
    }
}
