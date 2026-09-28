package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.ecom.EcomFavouriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The medicines a customer saved for later.
 *
 * <p>Signed-in only, and scoped to the token's own customer — the caller never
 * names whose list it is, so there is no id to tamper with.
 */
@RestController
@RequestMapping("/api/ecom/me/favourites")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Favourites", description = "A customer's saved medicines")
public class EcomFavouriteController {

    private final EcomFavouriteService favourites;

    @Operation(summary = "Everything this customer saved, priced as it stands today",
               description = "A saved listing whose product has since been retired comes back "
                           + "with available:false rather than being dropped silently.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<EcomFavouriteService.Saved>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(favourites.list()));
    }

    @Operation(summary = "Just the keys (branchId:productId), to light the hearts on a grid already loaded")
    @GetMapping("/keys")
    public ResponseEntity<ApiResponse<Map<String, Object>>> keys() {
        var k = favourites.keys();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("keys", k, "count", k.size())));
    }

    @Operation(summary = "Save or unsave one listing",
               description = "One endpoint, because the heart is one control: the response says "
                           + "which state it ended in.")
    @PostMapping("/toggle")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toggle(@RequestBody Toggle body) {
        boolean saved = favourites.toggle(body.branchId(), body.productId());
        return ResponseEntity.ok(ApiResponse.ok(
                saved ? "Saved to your favourites" : "Removed from your favourites",
                Map.of("saved", saved, "branchId", body.branchId(), "productId", body.productId())));
    }

    public record Toggle(Long branchId, Long productId) { }
}
