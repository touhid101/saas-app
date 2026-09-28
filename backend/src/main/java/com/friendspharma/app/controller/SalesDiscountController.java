package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.dto.request.SalesDiscountRequest;
import com.friendspharma.app.dto.response.SalesDiscountResponse;
import com.friendspharma.app.service.interfaces.SalesDiscountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/sales-discount")
@RequiredArgsConstructor
@Tag(name = "Sales Discount", description = "Company & product-wise discount tiers (Box/Strip/PCS/Special)")
public class SalesDiscountController {

    private final SalesDiscountService discountService;

    // ── CREATE ─────────────────────────────────────────────────────────────

    /* Authorisation is the matrix right on Sales Discount% (MOD08), checked in
       the service — see SalesDiscountServiceImpl.requireRight. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new discount rule")
    public ApiResponse<SalesDiscountResponse.SalesDiscountDto> create(
            @Valid @RequestBody SalesDiscountRequest.CreateSalesDiscountRequest req) {
        return ApiResponse.success(discountService.create(req), "Discount rule created");
    }

    // ── UPDATE ─────────────────────────────────────────────────────────────

    @PutMapping("/{pidDisc}")
    @Operation(summary = "Update discount percentage or active flag")
    public ApiResponse<SalesDiscountResponse.SalesDiscountDto> update(
            @PathVariable Long pidDisc,
            @Valid @RequestBody SalesDiscountRequest.UpdateSalesDiscountRequest req) {
        return ApiResponse.success(discountService.update(pidDisc, req));
    }

    // ── DELETE ─────────────────────────────────────────────────────────────

    @DeleteMapping("/{pidDisc}")
    @Operation(summary = "Delete a discount rule")
    public ApiResponse<Void> delete(@PathVariable Long pidDisc) {
        discountService.delete(pidDisc);
        return ApiResponse.success(null, "Discount rule deleted");
    }

    // ── QUERIES ────────────────────────────────────────────────────────────

    @GetMapping("/org/{orgId}")
    @Operation(summary = "List all active discount rules for an organisation")
    public ApiResponse<List<SalesDiscountResponse.SalesDiscountDto>> listByOrg(
            @PathVariable Long orgId) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(discountService.listByOrg(orgId));
    }

    @GetMapping("/org/{orgId}/company/{companyId}")
    @Operation(summary = "List discount rules for a specific company")
    public ApiResponse<List<SalesDiscountResponse.SalesDiscountDto>> listByCompany(
            @PathVariable Long orgId,
            @PathVariable Long companyId) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(discountService.listByCompany(orgId, companyId));
    }

    @GetMapping("/org/{orgId}/product/{productId}")
    @Operation(summary = "List discount rules for a specific product")
    public ApiResponse<List<SalesDiscountResponse.SalesDiscountDto>> listByProduct(
            @PathVariable Long orgId,
            @PathVariable Long productId) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(discountService.listByProduct(orgId, productId));
    }

    // ── EFFECTIVE DISCOUNT (called from POS / Sales line) ─────────────────

    @GetMapping("/effective")
    @Operation(summary = "Resolve effective discount % for a product+tier during sales",
               description = "Priority: product-specific > company-specific > org-wide. Returns 0 if no rule.")
    public ApiResponse<SalesDiscountResponse.EffectiveDiscountDto> effective(
            @RequestParam(required = false) Long orgId,
            @RequestParam Long productId,
            @RequestParam Long companyId,
            @RequestParam String tier) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        SalesDiscountRequest.EffectiveDiscountQuery q = new SalesDiscountRequest.EffectiveDiscountQuery();
        q.setOrgId(orgId);
        q.setProductId(productId);
        q.setCompanyId(companyId);
        q.setTier(tier);
        return ApiResponse.success(discountService.getEffectiveDiscount(q));
    }

    @PostMapping("/effective")
    @Operation(summary = "Resolve effective discount % (POST body)")
    public ApiResponse<SalesDiscountResponse.EffectiveDiscountDto> effectivePost(
            @Valid @RequestBody SalesDiscountRequest.EffectiveDiscountQuery query) {
        return ApiResponse.success(discountService.getEffectiveDiscount(query));
    }

    // ── QUICK LOOKUP (used inline during POS) ─────────────────────────────

    @GetMapping("/pct")
    @Operation(summary = "Return just the discount percentage — lightweight call for POS")
    public ApiResponse<BigDecimal> pct(
            @RequestParam(required = false) Long orgId,
            @RequestParam Long productId,
            @RequestParam Long companyId,
            @RequestParam String tier) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(
                discountService.resolveEffectiveDiscount(orgId, productId, companyId, tier));
    }
}
