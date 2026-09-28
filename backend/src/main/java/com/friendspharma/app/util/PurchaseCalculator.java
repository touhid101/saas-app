package com.friendspharma.app.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Purchase line and invoice arithmetic — the single source of truth for every
 * derived money value on a purchase.
 *
 * <p>Pure functions, no Spring, no I/O, so the rules are unit testable and the
 * create and update paths cannot drift apart.
 *
 * <p>The line formula is a faithful port of the APEX expression, including its
 * rounding sequence. The two branches are <em>not</em> symmetric and the
 * asymmetry is deliberate — it is reproduced rather than tidied up, because
 * these totals must keep matching the legacy system:
 *
 * <pre>
 *   MNP:  base  = ROUND(qty × (tp + igta) − (qty × tp × tpPct / 100), 2)
 *         total = ROUND(base − base × discPct / 100, 2) − qty × flatDiscount
 *
 *   MRP:  total = ROUND(qty × (mrp − (mrp × discPct / 100 + flatDiscount)), 2)
 * </pre>
 *
 * Note where the flat discount lands: MNP subtracts {@code qty × flat} after the
 * final rounding, MRP subtracts {@code flat} per unit before it. Nulls are
 * treated as zero throughout, mirroring {@code NVL(x, 0)}.
 */
public final class PurchaseCalculator {

    /** Costing basis for a purchase invoice. */
    public static final String TYPE_MNP = "MNP";
    public static final String TYPE_MRP = "MRP";

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PurchaseCalculator() {}

    /**
     * Total price for one purchase line.
     *
     * @param purchaseType MNP or MRP; anything else is treated as MNP
     * @param quantity     units purchased
     * @param tpPrice      trade price (stored as MNP_PRICE on the line)
     * @param igta         IGTA snapshot from the product
     * @param mrpPrice     market retail price
     * @param tpPct        trade-price percentage deducted on the MNP basis
     * @param discountPct  line discount percentage
     * @param flatDiscount flat discount (per unit on MRP, per unit then × qty on MNP)
     */
    public static BigDecimal calculateLineTotal(String purchaseType,
                                                BigDecimal quantity,
                                                BigDecimal tpPrice,
                                                BigDecimal igta,
                                                BigDecimal mrpPrice,
                                                BigDecimal tpPct,
                                                BigDecimal discountPct,
                                                BigDecimal flatDiscount) {
        BigDecimal qty  = nvl(quantity);
        BigDecimal disc = nvl(discountPct);
        BigDecimal flat = nvl(flatDiscount);

        if (TYPE_MRP.equalsIgnoreCase(purchaseType)) {
            BigDecimal mrp = nvl(mrpPrice);
            // mrp − (mrp × disc/100 + flat)
            BigDecimal perUnit = mrp.subtract(
                    mrp.multiply(disc).divide(HUNDRED, 10, RoundingMode.HALF_UP).add(flat));
            return round(qty.multiply(perUnit));
        }

        BigDecimal tp = nvl(tpPrice);
        // qty × (tp + igta) − (qty × tp × tpPct/100)
        BigDecimal gross = qty.multiply(tp.add(nvl(igta)));
        BigDecimal tpCut = qty.multiply(tp).multiply(nvl(tpPct)).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        BigDecimal base  = round(gross.subtract(tpCut));

        BigDecimal afterDisc = round(base.subtract(
                base.multiply(disc).divide(HUNDRED, 10, RoundingMode.HALF_UP)));

        // Flat discount applies outside the rounding on this branch.
        return afterDisc.subtract(qty.multiply(flat)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Line gross before any discount — what the line would cost at list price.
     * Used for the invoice's TOTAL_DISCOUNT figure, which is the difference
     * between gross and net across all lines.
     */
    public static BigDecimal calculateLineGross(String purchaseType,
                                                BigDecimal quantity,
                                                BigDecimal tpPrice,
                                                BigDecimal igta,
                                                BigDecimal mrpPrice,
                                                BigDecimal tpPct) {
        /* tpPct is zeroed here along with the other two, and that is the fix.
           It used to be passed through, so the trade-price cut was counted as
           part of "gross" and never appeared in TOTAL_DISCOUNT — on
           PU260900007 a 3% TP cut worth 5.62 vanished, and the invoice
           reported 21.46 given away where the supplier had actually given
           27.08. TP% is a discount off the list price like any other; the
           comment above already said this method means "before any discount".

           On the MRP basis tpPct is not used at all, so zeroing it changes
           nothing there. */
        return calculateLineTotal(purchaseType, quantity, tpPrice, igta, mrpPrice,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Rolls line totals up into the invoice figures.
     *
     * <p>Order matters: the invoice-level discount applies to the summed line
     * totals, then tax and service charge are added to what remains.
     *
     * @param lineTotalSum  sum of every line's TOTAL_PRICE
     * @param lineGrossSum  sum of every line's pre-discount value
     * @param invoiceDisPct invoice-level discount percentage
     * @param totalTax      tax amount for the invoice
     * @param serviceCharge service charge for the invoice
     * @param givenAmount   amount actually paid
     */
    public static InvoiceTotals calculateInvoice(BigDecimal lineTotalSum,
                                                 BigDecimal lineGrossSum,
                                                 BigDecimal invoiceDisPct,
                                                 BigDecimal totalTax,
                                                 BigDecimal serviceCharge,
                                                 BigDecimal cashbackAmount,
                                                 BigDecimal givenAmount) {
        BigDecimal total   = round(nvl(lineTotalSum));
        BigDecimal disPct  = nvl(invoiceDisPct);
        BigDecimal disAmt  = round(total.multiply(disPct).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        BigDecimal tax     = round(nvl(totalTax));
        BigDecimal service = round(nvl(serviceCharge));
        BigDecimal given   = round(nvl(givenAmount));
        BigDecimal cashback = round(nvl(cashbackAmount));

        /* [V68] Cashback comes off what is owed, after tax and service charge —
           it is money handed back on the whole bill, not a reduction in the
           price of the goods before tax was worked out. It is kept apart from
           invoiceDisAmt because the two are different events with different
           lives: a discount is a price, a cashback is an incentive. */
        BigDecimal payable = round(total.subtract(disAmt).add(tax).add(service).subtract(cashback));

        // Line-level discounts plus the invoice-level cut, so the invoice can
        // report what was given away in total.
        BigDecimal lineDiscount  = round(nvl(lineGrossSum).subtract(total));
        BigDecimal totalDiscount = round(lineDiscount.max(BigDecimal.ZERO).add(disAmt));

        return new InvoiceTotals(total, disPct, disAmt, totalDiscount, tax, service,
                cashback, payable, given, round(payable.subtract(given)));
    }

    private static BigDecimal round(BigDecimal v) {
        return nvl(v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Immutable invoice roll-up, so a caller cannot update one field and leave the rest stale. */
    public record InvoiceTotals(
            BigDecimal totalAmount,
            BigDecimal invoiceDisPct,
            BigDecimal invoiceDisAmt,
            BigDecimal totalDiscount,
            BigDecimal totalTax,
            BigDecimal serviceCharge,
            /** [V68] Supplier incentive taken off this invoice. */
            BigDecimal cashbackAmount,
            BigDecimal amountToBePaid,
            BigDecimal givenAmount,
            /** Positive = still owed to the supplier, negative = overpaid. */
            BigDecimal dueAmount
    ) {}
}
