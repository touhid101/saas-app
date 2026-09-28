package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.EcomCatalogueResponses;
import com.friendspharma.app.service.impl.ecom.EcomCatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The marketplace catalogue. Open: a guest browses at retail prices; a
 * signed-in customer's token (if sent) prices the same rows at their tier.
 */
@RestController
@RequestMapping("/api/ecom/public/catalogue")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Catalogue", description = "Medicines at the online pharmacies, with live stock and the caller's price")
public class EcomPublicCatalogueController {

    private final EcomCatalogueService catalogue;

    @Operation(summary = "Search medicines across the online pharmacies (or one), priced for the caller, in-stock first",
               description = "`sort` takes new, price-asc, price-desc or discount; every order still puts "
                           + "what is in stock first, because a list led by things nobody can buy is a list of "
                           + "prices rather than of offers. The response carries category, pharmacy and "
                           + "manufacturer facets counted from the same rows.")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<ApiResponse<EcomCatalogueResponses.Page>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return ResponseEntity.ok(ApiResponse.ok(catalogue.search(q, branchId, category, company, subCategory, sort, inStockOnly, lat, lng, radiusKm, page, size)));
    }

    @Operation(summary = "One medicine at one pharmacy, priced for the caller, with live stock per unit",
               description = "Pass branchId — it is the pharmacy the customer was looking at. A medicine "
                           + "held org-wide is listed at every online branch of that organisation with "
                           + "each counter's own stock, so without a branch the answer is whichever one "
                           + "came first, which is rarely the one they tapped.")
    @SecurityRequirements
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<EcomCatalogueResponses.Item>> get(
            @PathVariable Long productId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok(catalogue.get(productId, branchId, lat, lng)));
    }
}
