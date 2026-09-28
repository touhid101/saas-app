package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.EcomCompareResponses;
import com.friendspharma.app.service.impl.ecom.EcomCompareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The same medicine at every online pharmacy that stocks it, at the caller's price. */
@RestController
@RequestMapping("/api/ecom/public/compare")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Compare", description = "One medicine, every pharmacy, ranked by what you would pay")
public class EcomPublicCompareController {

    private final EcomCompareService compare;

    @Operation(summary = "Compare one medicine across the online pharmacies; unit defaults to the cheapest the caller's tier may buy")
    @SecurityRequirements
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<EcomCompareResponses.Comparison>> compare(
            @PathVariable Long productId,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(ApiResponse.ok(compare.compare(productId, unit, lat, lng, radiusKm)));
    }
}
