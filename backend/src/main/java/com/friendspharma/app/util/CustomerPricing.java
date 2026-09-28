package com.friendspharma.app.util;

import com.friendspharma.app.util.SalesPriceCalculator.UnitPrices;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a customer may buy, and at what price.
 *
 * <p>Two rules, and they are separate:
 *
 * <pre>
 *   WHOLESALER   BOX, STRIP, PCS      regular prices
 *   SPECIAL      BOX, STRIP, PCS      special prices
 *   RETAILER     PCS only             regular (wholesaler) piece price
 * </pre>
 *
 * <p>The tier decides <em>which units are sellable</em> and <em>which price
 * column applies</em>, and those are not the same question. A retailer buys
 * single pieces at the wholesaler's piece rate — the restriction is on the
 * unit, not on the price list.
 *
 * <p>Kept as pure functions so the rule is unit-testable and the API, the sale
 * write path and the UI cannot drift apart in how they read it.
 */
public final class CustomerPricing {

    private CustomerPricing() {}

    public enum Tier {
        /** Buys in any unit at the regular price list. */
        WHOLESALER,
        /** Buys pieces only, at the wholesaler's piece rate. */
        RETAILER,
        /** Buys in any unit at the special price list. */
        SPECIAL
    }

    private static final List<String> ALL_UNITS = List.of(
            StockUnitConverter.BOX, StockUnitConverter.STRIP, StockUnitConverter.PCS);
    private static final List<String> PCS_ONLY = List.of(StockUnitConverter.PCS);

    /**
     * Reads a stored {@code CUSTOMER_TYPE} into a tier.
     *
     * <p>Tolerant of the spellings already in the data — "Wholesaler",
     * "WHOLESALE" and "wholesaler" are the same thing. An unrecognised or
     * missing value falls back to {@link Tier#RETAILER}, the most restrictive
     * option: a customer whose terms nobody recorded should not silently get
     * wholesale box pricing.
     */
    public static Tier tierOf(String customerType) {
        if (customerType == null || customerType.isBlank()) return Tier.RETAILER;
        String t = customerType.trim().toUpperCase();

        if (t.startsWith("WHOLESALE")) return Tier.WHOLESALER;
        if (t.startsWith("SPECIAL"))   return Tier.SPECIAL;
        if (t.startsWith("RETAIL"))    return Tier.RETAILER;
        return Tier.RETAILER;
    }

    /** The units this tier may transact in. */
    public static List<String> allowedUnits(Tier tier) {
        return tier == Tier.RETAILER ? PCS_ONLY : ALL_UNITS;
    }

    public static boolean allows(Tier tier, String unit) {
        return allowedUnits(tier).contains(StockUnitConverter.normalise(unit));
    }

    /**
     * The price this tier pays for one {@code unit}.
     *
     * <p>Returns null when the tier cannot buy in that unit at all, or when the
     * product has no percentage configured for it. Both mean "not sellable this
     * way", and neither should fall back to MRP — quietly charging full list
     * price because a percentage was missing is worse than refusing the line.
     */
    public static BigDecimal priceFor(Tier tier, String unit, UnitPrices prices) {
        if (prices == null || !allows(tier, unit)) return null;

        String u = StockUnitConverter.normalise(unit);
        return switch (tier) {
            // A retailer is restricted to pieces, but pays the wholesaler's
            // piece rate — the restriction is on the unit, not the price list.
            case RETAILER   -> StockUnitConverter.PCS.equals(u) ? prices.pcsPrice() : null;

            case WHOLESALER -> switch (u) {
                case StockUnitConverter.BOX   -> prices.boxPrice();
                case StockUnitConverter.STRIP -> prices.stripPrice();
                case StockUnitConverter.PCS   -> prices.pcsPrice();
                default -> null;
            };

            case SPECIAL -> switch (u) {
                case StockUnitConverter.BOX   -> prices.specialBoxPrice();
                case StockUnitConverter.STRIP -> prices.specialStripPrice();
                case StockUnitConverter.PCS   -> prices.specialPcsPrice();
                default -> null;
            };
        };
    }

    /** The MRP the tier's price for {@code unit} was derived from, for display. */
    public static BigDecimal mrpFor(String unit, UnitPrices prices) {
        if (prices == null) return null;
        return switch (StockUnitConverter.normalise(unit)) {
            case StockUnitConverter.BOX   -> prices.boxMrp();
            case StockUnitConverter.STRIP -> prices.stripMrp();
            case StockUnitConverter.PCS   -> prices.pcsMrp();
            default -> null;
        };
    }

    /** How the tier should read in a message or on screen. */
    public static String label(Tier tier) {
        return switch (tier) {
            case WHOLESALER -> "Wholesaler";
            case RETAILER   -> "Retailer";
            case SPECIAL    -> "Special";
        };
    }

    /**
     * Why a unit is unavailable, phrased for the person who tried to sell it.
     * Null when it is available.
     *
     * <p><b>The reason has to name the thing that would fix it.</b> This used to
     * answer "set the percentage in Sales Discount%" for every refusal. On the
     * sale path that is now almost always wrong: {@link SalesPriceCalculator#unitPricesForSale}
     * prices an unset percentage at MRP, so a unit refused there is refused for a
     * different reason entirely — and the advice sent somebody to a screen where
     * they could set a percentage, watch nothing change, and conclude the till
     * was broken. Nearly half the catalogue has no packing, so nearly half of
     * these messages pointed at the wrong screen.
     *
     * <p>Three faults, fixed in three places: the tier's own unit rules, the
     * packing and MRP in Product Master, and the percentage in Sales Discount%.
     * Which one is named depends on which input is actually missing.
     */
    public static String unavailableReason(Tier tier, String unit, UnitPrices prices) {
        String u = StockUnitConverter.normalise(unit);

        if (!allows(tier, u)) {
            return label(tier) + " customers can only buy in "
                 + String.join(", ", allowedUnits(tier)) + ".";
        }
        if (priceFor(tier, u, prices) == null) {
            /* Which of the two inputs is missing decides the advice, so read it
               off the prices rather than guess. A unit with no share of MRP
               cannot be priced at any percentage; a unit that has one, and still
               has no price, is waiting on the percentage. */
            BigDecimal unitMrp = mrpFor(u, prices);

            if (unitMrp == null || unitMrp.signum() <= 0) {
                if (StockUnitConverter.BOX.equals(u)) {
                    return "This product has no MRP set. Add it in Product Master.";
                }
                return "This product has no " + u.toLowerCase() + " packing set, so a "
                     + u.toLowerCase() + " price cannot be worked out. Set the box and "
                     + "strip sizes in Product Master.";
            }

            return "No " + label(tier).toLowerCase() + " price is set for " + u
                 + ". Set the percentage in Sales Discount%.";
        }
        return null;
    }
}
