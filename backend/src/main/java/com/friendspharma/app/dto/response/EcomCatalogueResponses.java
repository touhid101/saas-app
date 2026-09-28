package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the marketplace shows for a medicine at one pharmacy. Prices are the
 * caller's tier only; MRP is shown as the reference it is on the pack. No
 * costs, no batches, no expiry dates, no percentages.
 */
public final class EcomCatalogueResponses {
    private EcomCatalogueResponses() {}

    @Getter @Builder
    public static class UnitOffer {
        private final String unit;            // BOX | STRIP | PCS
        /** Whether the caller's tier may buy this unit at all (a retailer: PCS only). */
        private final boolean allowed;
        /** Pieces in one of this unit; null when the pharmacy has not set the packing. */
        private final Integer pcsPerUnit;
        /** The unit's share of MRP, before any discount. */
        private final BigDecimal mrp;
        /** The tier price before the standing discount. */
        private final BigDecimal listPrice;
        /** Standing discount from the pharmacy's discount rules, percent. */
        private final BigDecimal discountPct;
        /** What one unit costs the caller. Null when not sellable in this unit. */
        private final BigDecimal price;
        /** Whole units the pharmacy can supply right now. */
        private final Integer inStock;
        /** Why the unit is unavailable, when it is. */
        private final String reason;
    }

    @Getter @Builder
    public static class Item {
        private final Long productId;
        private final Long globalProductId;
        private final String name;
        private final String strength;
        private final String generic;
        private final String company;
        private final String category;
        private final Long categoryId;
        /** The pharmacy's own sub-category, where it set one. Most rows have none. */
        private final String subCategory;
        private final Long subCategoryId;
        private final String productType;
        private final String imageUrl;
        private final boolean prescription;
        private final Long branchId;
        private final String branchName;
        /** True only where staff have recorded seeing this pharmacy's licence (V114). */
        private final boolean branchVerified;
        private final Double branchDistanceKm;
        private final Integer pcsPerBox;
        private final Integer pcsPerStrip;
        /** Live pieces on the shelf, across lots that are not expired or blocked. */
        private final BigDecimal availablePcs;
        private final boolean inStock;
        /**
         * The pharmacy's own reorder level, in pieces. The storefront draws its
         * stock bar against this, so "running low" means what this pharmacy says
         * it means rather than a number a shop window invented. Null when the
         * pharmacy has not set one, and no bar is drawn then.
         */
        private final BigDecimal reorderPcs;
        /**
         * When the pharmacy first put this product on its books. It is what
         * "new arrivals" sorts on, so that shelf is genuinely the newest thing
         * the counter stocks rather than a selection somebody curated.
         */
        private final java.time.LocalDateTime addedAt;

        /**
         * Pieces actually sold online at this pharmacy over the last seven
         * days, cancellations and returns excluded.
         *
         * <p>This lives on the item rather than only on an offer because every
         * card in the storefront shows it — the flash rail, the grids, the
         * pharmacy shelf, the favourites page and the product page itself. It
         * used to be computed in the offers service alone, which is why only
         * flash cards had it.
         */
        private final long soldPcs;
        /**
         * Where the bar sits: sold ÷ (sold + still in stock). Both halves are
         * counted figures, so the proportion is the real split between what
         * went and what is left, not a target somebody chose. Null when
         * nothing has sold and there is nothing to divide.
         */
        private final Integer soldBarPct;
        /** Tier the prices were computed for. */
        private final String tier;
        /** The unit the card leads with (the cheapest allowed in-stock unit by piece). */
        private final String defaultUnit;
        private final List<UnitOffer> units;
    }

    @Getter @Builder
    public static class Facet {
        private final Long id;
        private final String name;
        private final long count;
    }

    @Getter @Builder
    public static class Page {
        private final List<Item> items;
        private final int page;
        private final int size;
        private final long total;
        private final int totalPages;
        private final String tier;
        private final List<Facet> categories;
        private final List<Facet> pharmacies;
        /** Manufacturers in these results, counted from the same rows as the categories. */
        private final List<Facet> companies;
        /**
         * Sub-categories in these results. Usually short or empty: this product
         * master keeps sub-categories optional and few rows carry one, so the
         * mega menu falls back to manufacturers rather than drawing a blank
         * column.
         */
        private final List<Facet> subCategories;
    }
}
