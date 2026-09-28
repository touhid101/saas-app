package com.friendspharma.app.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Mirrors calc.js formulas exactly — all purchase and sales line/invoice calculations.
 * Never used in controllers — only in service layer.
 */
public final class TransactionCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private TransactionCalculator() {}

    /** calc.js: calculateLineTotal(pcs, tp) = pcs × tp */
    public static BigDecimal calculateLineTotal(BigDecimal qty, BigDecimal unitPrice) {
        if (qty == null || unitPrice == null) return BigDecimal.ZERO;
        return qty.multiply(unitPrice).setScale(SCALE, RM);
    }

    /** calc.js: calculateDiscount(lineTotal, discPct) = lineTotal × (discPct / 100) */
    public static BigDecimal calculateDiscount(BigDecimal lineTotal, BigDecimal discPct) {
        if (discPct == null || discPct.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return lineTotal.multiply(discPct).divide(BigDecimal.valueOf(100), SCALE, RM);
    }

    /** calc.js: calculateTax(lineTotal, vatPct) */
    public static BigDecimal calculateTax(BigDecimal lineTotal, BigDecimal vatPct) {
        if (vatPct == null || vatPct.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return lineTotal.multiply(vatPct).divide(BigDecimal.valueOf(100), SCALE, RM);
    }

    /**
     * calc.js: calculateInvoiceTotal(lines, invoiceDiscPct, deliveryCharge)
     *   subtotal = SUM(lineTotal)
     *   invoiceDisc = subtotal × invoiceDiscPct / 100
     *   total = subtotal - invoiceDisc + deliveryCharge
     */
    public static InvoiceTotals calculateInvoiceTotals(
            List<BigDecimal> lineTotals,
            BigDecimal invoiceDiscPct,
            BigDecimal deliveryCharge) {

        BigDecimal subtotal = lineTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RM);
        BigDecimal invDiscAmt = calculateDiscount(subtotal, invoiceDiscPct);
        BigDecimal dc = deliveryCharge != null ? deliveryCharge : BigDecimal.ZERO;
        BigDecimal amountTobePaid = subtotal.subtract(invDiscAmt).add(dc).setScale(SCALE, RM);
        return new InvoiceTotals(subtotal, invDiscAmt, amountTobePaid);
    }

    /**
     * The same totals, but with the invoice discount given as an amount.
     *
     * <p>For a caller that knows what the discount is worth rather than what
     * percentage it represents — a coupon, which is a sum of money. The amount
     * is capped at the subtotal, because a discount larger than the goods is a
     * refund, and clamped at zero.
     *
     * @param exactAmount the discount in currency, never a percentage
     */
    public static InvoiceTotals calculateInvoiceTotals(
            List<BigDecimal> lineTotals,
            BigDecimal exactAmount,
            boolean asAmount,
            BigDecimal deliveryCharge) {

        BigDecimal subtotal = lineTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RM);
        BigDecimal discount = exactAmount == null ? BigDecimal.ZERO : exactAmount.setScale(SCALE, RM);
        if (discount.signum() < 0) discount = BigDecimal.ZERO;
        if (discount.compareTo(subtotal) > 0) discount = subtotal;
        BigDecimal dc = deliveryCharge != null ? deliveryCharge : BigDecimal.ZERO;
        BigDecimal amountTobePaid = subtotal.subtract(discount).add(dc).setScale(SCALE, RM);
        return new InvoiceTotals(subtotal, discount, amountTobePaid);
    }

    /** calc.js: calculateDue(total, paid) */
    public static BigDecimal calculateDue(BigDecimal total, BigDecimal paid) {
        if (paid == null) return total;
        BigDecimal due = total.subtract(paid).setScale(SCALE, RM);
        return due.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : due;
    }

    /** calc.js: calculateChange(paid, total) */
    public static BigDecimal calculateChange(BigDecimal paid, BigDecimal total) {
        if (paid == null) return BigDecimal.ZERO;
        BigDecimal change = paid.subtract(total).setScale(SCALE, RM);
        return change.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : change;
    }

    /**
     * SalesDiscount: apply multi-tier discount based on unit mode
     * Returns the effective discount % for BOX/STRIP/PCS/Special
     */
    public static BigDecimal getEffectiveDiscountPct(
            String salesUnit,
            boolean isSpecialCustomer,
            BigDecimal boxDisc, BigDecimal stripDisc, BigDecimal pcsDisc, BigDecimal specialDisc) {
        if (isSpecialCustomer && specialDisc != null && specialDisc.compareTo(BigDecimal.ZERO) > 0)
            return specialDisc;
        if ("BOX".equalsIgnoreCase(salesUnit))   return boxDisc   != null ? boxDisc   : BigDecimal.ZERO;
        if ("STRIP".equalsIgnoreCase(salesUnit)) return stripDisc != null ? stripDisc : BigDecimal.ZERO;
        return pcsDisc != null ? pcsDisc : BigDecimal.ZERO;
    }

    /** Effective unit price after applying tier discount */
    public static BigDecimal applyDiscount(BigDecimal basePrice, BigDecimal discPct) {
        if (discPct == null || discPct.compareTo(BigDecimal.ZERO) == 0) return basePrice;
        BigDecimal disc = basePrice.multiply(discPct).divide(BigDecimal.valueOf(100), SCALE, RM);
        return basePrice.subtract(disc).setScale(SCALE, RM);
    }

    public record InvoiceTotals(BigDecimal subtotal, BigDecimal invoiceDiscAmt, BigDecimal amountTobePaid) {}
}
