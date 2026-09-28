package com.friendspharma.app.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Sales% → sale price, the single source of truth for the SRS rule:
 *
 * <pre>
 *   Unit Sales Price = MRP − (MRP × Sales Percentage / 100)
 * </pre>
 *
 * <p>Maintained separately per BOX, STRIP/LEAF and PCS, and again for special
 * customers. Pure functions, no Spring, no I/O, so the rule is unit testable
 * and the read and write paths cannot drift.
 *
 * <p>A null percentage means the unit is <em>not priced</em> — distinct from
 * 0%, which prices it at full MRP. The SRS treats an unpriced unit as not
 * sellable in that unit, so {@link #salePrice} returns null rather than
 * silently falling back to MRP.
 */
public final class SalesPriceCalculator {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private SalesPriceCalculator() {}

    /** The six percentages a product carries, in the order the UI shows them. */
    public enum Unit { BOX, STRIP, PCS }

    /**
     * Sale price for one unit.
     *
     * @param mrp the product's MRP
     * @param salesPct percentage off MRP; null means the unit is not priced
     * @return the sale price rounded to 2dp, or null when unpriced
     */
    public static BigDecimal salePrice(BigDecimal mrp, BigDecimal salesPct) {
        if (salesPct == null) return null;
        BigDecimal base = nvl(mrp);
        return base.subtract(base.multiply(salesPct).divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Money saved against MRP at this percentage — what the customer sees as
     * the discount. Null when the unit is unpriced.
     */
    public static BigDecimal discountAmount(BigDecimal mrp, BigDecimal salesPct) {
        BigDecimal price = salePrice(mrp, salesPct);
        return price == null ? null : nvl(mrp).setScale(SCALE, RoundingMode.HALF_UP).subtract(price);
    }

    /**
     * The reverse: the percentage that produces a given sale price. Lets the UI
     * offer "type the price you want" instead of only "type the percentage".
     *
     * @return the percentage at 4dp, or null when MRP is zero (no percentage
     *         can express a discount off nothing)
     */
    public static BigDecimal percentForPrice(BigDecimal mrp, BigDecimal targetPrice) {
        BigDecimal base = nvl(mrp);
        if (base.compareTo(BigDecimal.ZERO) == 0 || targetPrice == null) return null;
        return base.subtract(targetPrice)
                .multiply(HUNDRED)
                .divide(base, 4, RoundingMode.HALF_UP);
    }

    /**
     * All six prices for a product in one pass, so a caller cannot compute one
     * and leave the rest stale.
     */
    public static SalesPrices calculateAll(BigDecimal mrp,
                                           BigDecimal boxPct,   BigDecimal stripPct,   BigDecimal pcsPct,
                                           BigDecimal spcBoxPct, BigDecimal spcStripPct, BigDecimal spcPcsPct) {
        return new SalesPrices(
                salePrice(mrp, boxPct),      salePrice(mrp, stripPct),      salePrice(mrp, pcsPct),
                salePrice(mrp, spcBoxPct),   salePrice(mrp, spcStripPct),   salePrice(mrp, spcPcsPct));
    }

    /**
     * The six prices with MRP apportioned to the unit being priced.
     *
     * <p>{@link #calculateAll} applies every percentage to the raw MRP, which is
     * right for BOX — {@code POS_PRODUCT_INFO.MRP_PRICE} <em>is</em> the box
     * price, as {@code PosProductInfo.getMrpBox()} records — but wrong for a
     * strip or a piece, which are worth their share of the box before any
     * discount applies. A strip of a 12-strip box starts at MRP÷12, not at MRP.
     *
     * <p>The legacy view is no guide here: it divides MRP down to a
     * <em>per-piece</em> figure and then hands that back as the <em>box</em>
     * price, which {@code PROC_EXPIRY_WISE_SALES_QTY_DIS} then multiplies by a
     * piece count. Two errors that partly cancel. This does the arithmetic once,
     * correctly.
     *
     * <p>Where a pack size is unconfigured, that unit's price is null rather
     * than a guess — the same rule as an unset percentage.
     *
     * @param boxMrp      the product's MRP, being the price of one box
     * @param pcsPerBox   pieces in a box, or null when unconfigured
     * @param pcsPerStrip pieces in a strip, or null when unconfigured
     */
    public static UnitPrices unitPrices(BigDecimal boxMrp, Integer pcsPerBox, Integer pcsPerStrip,
                                        BigDecimal boxPct,    BigDecimal stripPct,    BigDecimal pcsPct,
                                        BigDecimal spcBoxPct, BigDecimal spcStripPct, BigDecimal spcPcsPct) {

        BigDecimal boxBase   = nvl(boxMrp);
        BigDecimal stripBase = apportion(boxBase, pcsPerStrip, pcsPerBox);
        BigDecimal pcsBase   = apportion(boxBase, 1, pcsPerBox);

        return new UnitPrices(
                boxBase, stripBase, pcsBase,
                salePrice(boxBase,   boxPct),
                stripBase == null ? null : salePrice(stripBase, stripPct),
                pcsBase   == null ? null : salePrice(pcsBase,   pcsPct),
                salePrice(boxBase,   spcBoxPct),
                stripBase == null ? null : salePrice(stripBase, spcStripPct),
                pcsBase   == null ? null : salePrice(pcsBase,   spcPcsPct));
    }

    /**
     * The same prices, with an unset percentage read as no discount.
     *
     * <p><b>For selling, not for maintaining.</b> On the Sales% screen a null
     * percentage means "nobody has priced this unit", and it must stay
     * distinguishable from a deliberate zero. At a till it cannot: a product
     * whose discount was never entered still has an MRP, is still on the shelf,
     * and a customer is still standing there. Refusing to price it removed the
     * product from the catalogue entirely, so the only way to sell a new line
     * was to go and set a discount on it first.
     *
     * <p>Zero is the honest reading of "no discount recorded" — sell it at MRP.
     * A pack size that is genuinely unconfigured still yields null for that
     * unit, because there is no MRP share to charge and guessing one would
     * invent a price.
     */
    public static UnitPrices unitPricesForSale(BigDecimal boxMrp, Integer pcsPerBox, Integer pcsPerStrip,
                                               BigDecimal boxPct,    BigDecimal stripPct,    BigDecimal pcsPct,
                                               BigDecimal spcBoxPct, BigDecimal spcStripPct, BigDecimal spcPcsPct) {
        /* No MRP is not "no discount", it is nothing to discount from. Reading a
           null percentage as zero would otherwise turn a product whose MRP was
           never entered into a product that sells for ৳0 — the one outcome worse
           than refusing to sell it. */
        if (boxMrp == null || boxMrp.compareTo(BigDecimal.ZERO) <= 0) {
            return new UnitPrices(boxMrp, null, null, null, null, null, null, null, null);
        }
        return unitPrices(boxMrp, pcsPerBox, pcsPerStrip,
                nvl(boxPct),    nvl(stripPct),    nvl(pcsPct),
                nvl(spcBoxPct), nvl(spcStripPct), nvl(spcPcsPct));
    }

    /**
     * A unit's share of the box MRP: {@code boxMrp × pcsInUnit ÷ pcsPerBox}.
     * Null when the packing needed to work it out is missing.
     */
    private static BigDecimal apportion(BigDecimal boxMrp, Integer pcsInUnit, Integer pcsPerBox) {
        if (pcsInUnit == null || pcsInUnit <= 0 || pcsPerBox == null || pcsPerBox <= 0) return null;
        return boxMrp.multiply(BigDecimal.valueOf(pcsInUnit))
                     .divide(BigDecimal.valueOf(pcsPerBox), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Every price a product can be sold at, with the MRP each is derived from.
     *
     * <p>Carrying the bases alongside the prices lets a report show the discount
     * without recomputing it, and makes an unpriced unit distinguishable from a
     * unit the product has no packing for.
     */
    public record UnitPrices(
            BigDecimal boxMrp,
            BigDecimal stripMrp,
            BigDecimal pcsMrp,
            BigDecimal boxPrice,
            BigDecimal stripPrice,
            BigDecimal pcsPrice,
            BigDecimal specialBoxPrice,
            BigDecimal specialStripPrice,
            BigDecimal specialPcsPrice
    ) {
        public boolean anyConfigured() {
            return boxPrice != null || stripPrice != null || pcsPrice != null
                || specialBoxPrice != null || specialStripPrice != null || specialPcsPrice != null;
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Immutable result of a full pricing pass. Nulls mark unpriced units. */
    public record SalesPrices(
            BigDecimal boxPrice,
            BigDecimal stripPrice,
            BigDecimal pcsPrice,
            BigDecimal specialBoxPrice,
            BigDecimal specialStripPrice,
            BigDecimal specialPcsPrice
    ) {
        /** True when every regular unit has a price — the minimum to sell the product. */
        public boolean regularComplete() {
            return boxPrice != null && stripPrice != null && pcsPrice != null;
        }

        /** True when at least one unit anywhere is priced. */
        public boolean anyConfigured() {
            return boxPrice != null || stripPrice != null || pcsPrice != null
                || specialBoxPrice != null || specialStripPrice != null || specialPcsPrice != null;
        }
    }
}
