package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.EcomPharmacyResponses;
import com.friendspharma.app.service.impl.ecom.EcomPharmacyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Pharmacy discovery. Open to anyone (a customer browses before signing in);
 * the coordinates travel as query parameters because they are the search,
 * not a record of the person — nothing is stored.
 */
@RestController
@RequestMapping("/api/ecom/public/pharmacies")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Pharmacies", description = "Pharmacies that sell online, nearest first")
public class EcomPublicPharmacyController {

    private final EcomPharmacyService pharmacies;

    @Operation(summary = "Online pharmacies; with lat/lng, those within radiusKm (default 10) nearest first, else all by name")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<ApiResponse<EcomPharmacyResponses.PharmacyPage>> list(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return ResponseEntity.ok(ApiResponse.ok(pharmacies.list(lat, lng, radiusKm, q, page, size)));
    }

    @Operation(summary = "One online pharmacy, with distance from lat/lng when given")
    @SecurityRequirements
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EcomPharmacyResponses.Pharmacy>> get(
            @PathVariable Long id,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok(pharmacies.get(id, lat, lng)));
    }
}
