package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.service.impl.ecom.EcomEligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The wholesale customer's shopping context: which pharmacies their approved
 * region permits, and which one they are buying from.
 *
 * <p>Nothing is taken from the request. The endpoint below has no parameters
 * at all, and that is the point — the eligible list is a fact about the
 * authenticated customer, not something a caller narrows or widens.
 */
@RestController
@RequestMapping("/api/ecom/wholesale")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Wholesale", description = "Region-eligible pharmacies for a wholesale account")
public class EcomWholesaleController {

    private final EcomEligibilityService eligibility;

    @Getter @Builder
    public static class Pharmacy {
        private final Long id;
        private final String name;
        private final String address;
        private final String phone;
        private final String logoUrl;
        private final String openingHours;
        private final String description;
        private final BigDecimal latitude;
        private final BigDecimal longitude;
        private final BigDecimal minOrderAmount;
        private final String tradeType;
    }

    @Operation(summary = "The wholesale pharmacies this account's approved region permits",
               description = "No parameters: the region comes from the authenticated customer's own record, "
                           + "never from the caller. A customer with no approved region is refused with the "
                           + "sentence that tells them what to complete.")
    @GetMapping("/eligible-pharmacies")
    public ResponseEntity<ApiResponse<Map<String, Object>>> eligible() {
        EcomEligibilityService.Scope scope = eligibility.scopeOf();
        List<Pharmacy> items = eligibility.eligibleWholesalePharmacies().stream().map(this::view).toList();

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "regionId",   scope.regionId() == null ? "" : scope.regionId(),
                "regionName", scope.regionName() == null ? "" : scope.regionName(),
                "items",      items,
                /* Said in words, because an empty list and "your region has no
                   pharmacy yet" look identical to a screen and mean quite
                   different things to a person. */
                "note", items.isEmpty()
                        ? "No pharmacy in " + (scope.regionName() == null ? "your region" : scope.regionName())
                          + " is selling wholesale online yet."
                        : items.size() + " pharmac" + (items.size() == 1 ? "y" : "ies")
                          + " in " + scope.regionName() + " sell to your account.")));
    }

    private Pharmacy view(AllBranch b) {
        return Pharmacy.builder()
                .id(b.getPidBranch()).name(b.getBranchName())
                .address(b.getBranchAddress()).phone(b.getBranchPhoneNo())
                .logoUrl(b.getLogoUrl()).openingHours(b.getEcomOpeningHours())
                .description(b.getEcomDescription())
                .latitude(b.getLatitude()).longitude(b.getLongitude())
                .minOrderAmount(b.getEcomMinOrderAmount())
                .tradeType(b.getBranchTradeType())
                .build();
    }
}
