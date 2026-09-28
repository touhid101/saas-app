package com.friendspharma.app.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Product Master pricing rules — the single source of truth for the derived
 * price fields. Pure functions, no Spring, no I/O, so the rules are unit
 * testable in isolation and cannot diverge between create and update paths.
 *
 * <p>Dependency chain:
 * <pre>
 *   MRP ─┐
 *        ├──> TP_PRICE ─┐
 *   CONV ┘              ├──> IGTA
 *                VAT ───┤
 *                       └──> BUY_PRICE
 * </pre>
 *
 * <p>The client never supplies TP_PRICE, IGTA or BUY_PRICE — the service layer
 * recomputes all three from MRP, CONVERSION_RATE and VAT_PCT on every write.
 *
 * <p>Rounding is HALF_UP to 2 decimal places, matching the {@code ROUND(x, 2)}
 * of the original Oracle formulas. Nulls are treated as zero, mirroring
 * {@code NVL(x, 0)}.
 */
public final class ProductPricingCalculator {

    /** Business default when the client does not supply a conversion rate. */
    public static final BigDecimal DEFAULT_CONVERSION_RATE = new BigDecimal("25.037");

    /** Business default when the client does not supply a VAT percentage. */
    public static final BigDecimal DEFAULT_VAT_PCT = new BigDecimal("17.40");

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ProductPricingCalculator() {}

    /**
     * TP_PRICE = ROUND(MRP - (MRP × CONVERSION_RATE / 100), 2)
     *
     * <p>Example: MRP 100, rate 25.037 → 100 - 25.037 = 74.963 → 74.96
     */
    public static BigDecimal calculateTpPrice(BigDecimal mrp, BigDecimal conversionRate) {
        BigDecimal m = nvl(mrp);
        BigDecimal r = nvl(conversionRate);
        return m.subtract(m.multiply(r).divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * IGTA = ROUND(TP_PRICE × VAT_PCT / 100, 2)
     *
     * <p>Example: TP 74.96, VAT 17.40 → 13.043... → 13.04
     */
    public static BigDecimal calculateIgta(BigDecimal tpPrice, BigDecimal vatPct) {
        return nvl(tpPrice).multiply(nvl(vatPct))
                .divide(HUNDRED, 10, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * BUY_PRICE = ROUND(TP_PRICE + (TP_PRICE × VAT_PCT / 100), 2)
     *
     * <p>Example: TP 74.96, VAT 17.40 → 74.96 + 13.043 = 88.003... → 88.00
     */
    public static BigDecimal calculateBuyPrice(BigDecimal tpPrice, BigDecimal vatPct) {
        BigDecimal tp = nvl(tpPrice);
        return tp.add(tp.multiply(nvl(vatPct)).divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Runs the whole chain in dependency order from the three user-editable
     * inputs. This is the only entry point the service layer uses, so a partial
     * recalculation is impossible.
     *
     * @param mrp            user-entered MRP (null treated as 0)
     * @param conversionRate user-entered rate (null falls back to 25.037)
     * @param vatPct         user-entered VAT % (null falls back to 17.40)
     */
    public static CalculatedPrices calculateAll(BigDecimal mrp,
                                                BigDecimal conversionRate,
                                                BigDecimal vatPct) {
        BigDecimal rate = conversionRate != null ? conversionRate : DEFAULT_CONVERSION_RATE;
        BigDecimal vat  = vatPct         != null ? vatPct         : DEFAULT_VAT_PCT;

        BigDecimal tp   = calculateTpPrice(mrp, rate);
        BigDecimal igta = calculateIgta(tp, vat);
        BigDecimal buy  = calculateBuyPrice(tp, vat);

        return new CalculatedPrices(
                nvl(mrp).setScale(SCALE, RoundingMode.HALF_UP), rate, tp, vat, igta, buy);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Result of a full pricing pass. Immutable so a caller cannot mutate one
     * field and leave the rest stale.
     */
    public record CalculatedPrices(
            BigDecimal mrp,
            BigDecimal conversionRate,
            BigDecimal tpPrice,
            BigDecimal vatPct,
            BigDecimal igta,
            BigDecimal buyPrice
    ) {}
}
