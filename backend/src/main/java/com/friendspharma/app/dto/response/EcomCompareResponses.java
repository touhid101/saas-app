package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * The same medicine at every online pharmacy that stocks it, ranked.
 *
 * <p>What makes two rows "the same medicine" is stated on the response
 * ({@link #matchedBy}) rather than assumed: the shared catalogue entry
 * ({@code PID_GLOBAL_PRODUCT}) where the pharmacies imported one, otherwise the
 * product name. A customer comparing prices deserves to know which.
 */
public final class EcomCompareResponses {
    private EcomCompareResponses() {}

    @Getter @Builder
    public static class Choice {
        private final int rank;
        private final Long productId;
        private final Long branchId;
        private final String branchName;
        private final String branchAddress;
        private final Double distanceKm;
        private final Boolean deliversHere;
        private final BigDecimal minOrderAmount;
        private final String openingHours;

        private final String unit;
        private final Integer pcsPerUnit;
        private final BigDecimal mrp;
        private final BigDecimal listPrice;
        private final BigDecimal discountPct;
        /** What one {@link #unit} costs the caller here. */
        private final BigDecimal price;
        /** Price per piece — what makes two pharmacies selling different pack sizes comparable. */
        private final BigDecimal pricePerPcs;
        /** More than the cheapest choice, per unit. Zero on the cheapest. */
        private final BigDecimal priceDiff;
        private final Integer inStock;
        private final BigDecimal availablePcs;
        private final boolean cheapest;
        private final boolean nearest;
    }

    @Getter @Builder
    public static class Comparison {
        private final Long productId;
        private final Long globalProductId;
        private final String name;
        private final String generic;
        private final String company;
        private final String category;
        private final String imageUrl;
        private final boolean prescription;
        /** GLOBAL_PRODUCT or PRODUCT_NAME — how the pharmacies were matched. */
        private final String matchedBy;
        private final String tier;
        /** The unit compared; the customer may switch between the units their tier allows. */
        private final String unit;
        private final List<String> comparableUnits;
        private final int pharmacyCount;
        private final int inStockCount;
        private final BigDecimal lowestPrice;
        private final BigDecimal highestPrice;
        /** What the cheapest choice saves against the dearest, per unit. */
        private final BigDecimal maxSaving;
        private final List<Choice> choices;
    }
}
