package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.EcomOfferResponses;
import com.friendspharma.app.service.impl.ecom.EcomOffersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is on offer, for the storefront's flash-sale rail and voucher wallet.
 *
 * <p>Open, like the catalogue: a guest sees retail savings and the vouchers
 * open to everyone; a signed-in customer's token prices the same medicines at
 * their tier and hides vouchers their account could never spend.
 */
@RestController
@RequestMapping("/api/ecom/public/offers")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Offers", description = "Live discounts and vouchers at the online pharmacies")
public class EcomPublicOffersController {

    private final EcomOffersService offers;

    @Operation(summary = "The live campaign, the reduced medicines and the spendable vouchers",
               description = "A countdown is returned only when a live coupon actually has an end date. "
                           + "Savings are MRP less the caller's own price, and the stock bar is the "
                           + "pharmacy's pieces in hand against its own reorder level.")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<ApiResponse<EcomOfferResponses.Offers>> offers(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "48") int size) {
        return ResponseEntity.ok(ApiResponse.ok(offers.offers(branchId, lat, lng, size)));
    }
}
