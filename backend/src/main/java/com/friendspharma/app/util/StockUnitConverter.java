package com.friendspharma.app.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BOX / STRIP / PCS conversion.
 *
 * <p>Stock is stored in pieces and only in pieces. Boxes and strips are how
 * people talk about it, so they are converted on the way in and rendered on the
 * way out — never stored. Keeping a single unit in the store is what makes the
 * arithmetic exact: a balance held in boxes would need a fraction to express
 * "eight and a half strips", and fractions in a balance drift.
 *
 * <p>The pack hierarchy, per the SRS:
 * <pre>
 *   POS_PRODUCT_INFO.PID_BOX → POS_BOX_INFO.NO_OF_LEAF   strips per box
 *   POS_BOX_INFO.PID_LEAF    → POS_LEAF_INFO.NO_OF_PCS   pieces per strip
 *
 *   pcsPerBox   = NO_OF_LEAF × NO_OF_PCS
 *   pcsPerStrip = NO_OF_PCS
 * </pre>
 */
public final class StockUnitConverter {

    public static final String BOX   = "BOX";
    public static final String STRIP = "STRIP";
    public static final String PCS   = "PCS";

    private StockUnitConverter() {}

    /**
     * Pieces in one unit of {@code unit}.
     *
     * <p>Returns {@code null} when the product has no packing configured for the
     * requested unit. Deliberately not 1: a missing pack size is unknown, not
     * "one piece", and defaulting it silently turns a 100-piece box into a
     * single tablet. Callers must decide what to do about the gap rather than
     * inherit a fabricated number.
     */
    public static Integer factorFor(String unit, Integer pcsPerBox, Integer pcsPerStrip) {
        String u = normalise(unit);
        return switch (u) {
            case BOX   -> positiveOrNull(pcsPerBox);
            case STRIP -> positiveOrNull(pcsPerStrip);
            case PCS   -> 1;
            default    -> null;
        };
    }

    /**
     * Converts a quantity expressed in {@code unit} into pieces.
     *
     * @throws IllegalArgumentException when the unit is unknown or the product
     *         has no pack size for it — both are configuration faults that must
     *         surface at the point of entry, not as a wrong balance later.
     */
    public static BigDecimal toPcs(BigDecimal qty, String unit, Integer pcsPerBox, Integer pcsPerStrip) {
        if (qty == null) return BigDecimal.ZERO;
        Integer factor = factorFor(unit, pcsPerBox, pcsPerStrip);
        if (factor == null) {
            throw new IllegalArgumentException(
                    "No pack size configured for unit '" + unit + "'. "
                    + "Set the product's box and strip packing before transacting in this unit.");
        }
        return qty.multiply(BigDecimal.valueOf(factor));
    }

    /** Pieces expressed as a decimal count of {@code unit}, for display only. */
    public static BigDecimal fromPcs(BigDecimal pcs, String unit, Integer pcsPerBox, Integer pcsPerStrip) {
        if (pcs == null) return null;
        Integer factor = factorFor(unit, pcsPerBox, pcsPerStrip);
        if (factor == null || factor == 0) return null;
        return pcs.divide(BigDecimal.valueOf(factor), 2, RoundingMode.HALF_UP);
    }

    /**
     * Splits pieces into whole boxes, whole strips and loose pieces.
     *
     * <p>What a stock report should actually show. "1500 pcs" is accurate and
     * useless to someone counting shelves; "30 box" is what they see.
     */
    public static Breakdown breakdown(BigDecimal pcs, Integer pcsPerBox, Integer pcsPerStrip) {
        if (pcs == null) return new Breakdown(0, 0, BigDecimal.ZERO);

        BigDecimal remaining = pcs;
        long boxes = 0, strips = 0;

        Integer perBox = positiveOrNull(pcsPerBox);
        if (perBox != null) {
            BigDecimal b = BigDecimal.valueOf(perBox);
            boxes = remaining.divideToIntegralValue(b).longValue();
            remaining = remaining.subtract(b.multiply(BigDecimal.valueOf(boxes)));
        }

        Integer perStrip = positiveOrNull(pcsPerStrip);
        if (perStrip != null) {
            BigDecimal s = BigDecimal.valueOf(perStrip);
            strips = remaining.divideToIntegralValue(s).longValue();
            remaining = remaining.subtract(s.multiply(BigDecimal.valueOf(strips)));
        }

        return new Breakdown(boxes, strips, remaining.stripTrailingZeros());
    }

    /** Renders a breakdown the way a shelf label reads: {@code "8 box 2 strip 3 pcs"}. */
    public static String describe(BigDecimal pcs, Integer pcsPerBox, Integer pcsPerStrip) {
        Breakdown b = breakdown(pcs, pcsPerBox, pcsPerStrip);
        StringBuilder sb = new StringBuilder();
        if (b.boxes() > 0)  sb.append(b.boxes()).append(" box");
        if (b.strips() > 0) sb.append(sb.isEmpty() ? "" : " ").append(b.strips()).append(" strip");
        if (b.pcs().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append(b.pcs().toPlainString()).append(" pcs");
        }
        return sb.isEmpty() ? "0 pcs" : sb.toString();
    }

    /** Upper-cased, trimmed unit name; blank and null both read as PCS. */
    public static String normalise(String unit) {
        if (unit == null || unit.isBlank()) return PCS;
        return unit.trim().toUpperCase();
    }

    public static boolean isKnownUnit(String unit) {
        String u = normalise(unit);
        return BOX.equals(u) || STRIP.equals(u) || PCS.equals(u);
    }

    private static Integer positiveOrNull(Integer v) {
        return (v != null && v > 0) ? v : null;
    }

    /** Whole boxes, whole strips, and the pieces left over. */
    public record Breakdown(long boxes, long strips, BigDecimal pcs) {}
}
