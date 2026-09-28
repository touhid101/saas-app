package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the marketplace may know about a pharmacy. Public fields only: no
 * organisation, company, subscription, report header or internal ids beyond
 * the branch id the customer will order from.
 */
public final class EcomPharmacyResponses {
    private EcomPharmacyResponses() {}

    @Getter @Builder
    public static class Pharmacy {
        private final Long id;
        private final String name;
        private final String address;
        private final String phone;
        private final String logoUrl;
        private final String description;
        private final String openingHours;
        private final BigDecimal latitude;
        private final BigDecimal longitude;
        private final BigDecimal deliveryRadiusKm;
        private final BigDecimal minOrderAmount;
        /** Straight-line km from the point the caller gave; null when no point was given or the pharmacy has none. */
        private final Double distanceKm;
        /** True when the caller's point is inside the pharmacy's delivery radius; null when unknown. */
        private final Boolean deliversHere;
        /** True only where staff have recorded seeing this pharmacy's drug licence (V114). */
        private final boolean verified;
    }

    @Getter @Builder
    public static class PharmacyPage {
        private final List<Pharmacy> items;
        private final int page;
        private final int size;
        private final long total;
        private final int totalPages;
        /** Whether the list was sorted by distance (a point was given) or by name. */
        private final boolean byDistance;
    }
}
