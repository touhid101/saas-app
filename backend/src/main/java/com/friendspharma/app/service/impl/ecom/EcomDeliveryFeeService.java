package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.AllBranch;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a pharmacy charges to deliver one order (docs/ecommerce Phase 9).
 *
 * <p>One place decides it, and both the cart and the checkout ask here, so the
 * figure a customer is shown while they shop is the figure the invoice carries
 * — computed from the same policy, on the server, from the pharmacy's own
 * settings. Nothing about the fee is ever taken from the client.
 *
 * <p><b>A fee is only charged when it can be justified.</b> DISTANCE needs two
 * points; a pharmacy always has coordinates, an address may not. Where the
 * distance is unknown the base fee applies and the quote says why, rather than
 * a fee being invented from a guessed journey.
 *
 * <p>A branch that has set nothing delivers free, which is what every branch
 * did before V108 gave them somewhere to say otherwise.
 */
@Service
@RequiredArgsConstructor
public class EcomDeliveryFeeService {

    public static final String NONE = "NONE", FLAT = "FLAT", DISTANCE = "DISTANCE";

    /** What the delivery costs, and the sentence that explains it. */
    @Getter @Builder
    public static class Quote {
        /** What will be added to the bill. Never null, never negative. */
        private final BigDecimal charge;
        /** One line a customer can read: "Free over ৳1,000", "৳60 to Uttara", … */
        private final String note;
        /** The subtotal at which this order would ship free, when there is one. */
        private final BigDecimal freeAbove;
        /** How much more they would have to add to reach it, when they are short. */
        private final BigDecimal addForFree;
        /** The distance the fee was worked out from, when one was known. */
        private final Double km;
        /** True when the order is going free because the basket is big enough. */
        private final boolean freeByThreshold;
    }

    /**
     * @param branch   the pharmacy filling the order
     * @param subtotal what the goods come to, before delivery
     * @param lat      the delivery point, when it is known
     * @param lng      the delivery point, when it is known
     */
    public Quote quote(AllBranch branch, BigDecimal subtotal, Double lat, Double lng) {
        BigDecimal goods = subtotal == null ? BigDecimal.ZERO : subtotal;
        String type = branch == null || branch.getEcomDeliveryFeeType() == null
                ? NONE : branch.getEcomDeliveryFeeType().trim().toUpperCase();

        BigDecimal freeAbove = positive(branch == null ? null : branch.getEcomDeliveryFreeAbove());

        if (NONE.equals(type)) {
            return Quote.builder().charge(BigDecimal.ZERO).note("Free delivery").build();
        }

        // The threshold beats the fee: a pharmacy that says "free over 1,000"
        // means free, whatever the distance works out to.
        if (freeAbove != null && goods.compareTo(freeAbove) >= 0) {
            return Quote.builder()
                    .charge(BigDecimal.ZERO)
                    .note("Free delivery on orders over " + plain(freeAbove))
                    .freeAbove(freeAbove).freeByThreshold(true)
                    .build();
        }

        BigDecimal base = zeroIfNull(branch.getEcomDeliveryFlatFee());
        BigDecimal charge = base;
        Double km = null;
        String note;

        if (DISTANCE.equals(type)) {
            km = distanceKm(branch, lat, lng);
            if (km == null) {
                /* No coordinates on the address. The base fee is charged and the
                   customer is told that is what happened — a per-kilometre fee
                   with no kilometres is a number somebody made up. */
                note = base.signum() == 0
                        ? "Free delivery"
                        : plain(base) + " delivery — add a location to your address for the exact fee";
            } else {
                BigDecimal perKm = zeroIfNull(branch.getEcomDeliveryFeePerKm());
                charge = base.add(perKm.multiply(BigDecimal.valueOf(km)));
                note = plain(round(charge)) + " delivery · about " + km + " km from the pharmacy";
            }
        } else {
            note = base.signum() == 0 ? "Free delivery" : plain(base) + " delivery";
        }

        charge = round(charge.max(BigDecimal.ZERO));

        return Quote.builder()
                .charge(charge)
                .note(charge.signum() == 0 ? "Free delivery" : note)
                .freeAbove(freeAbove)
                .addForFree(freeAbove == null ? null : round(freeAbove.subtract(goods).max(BigDecimal.ZERO)))
                .km(km)
                .build();
    }

    /** Straight-line kilometres, or null when either end has no coordinates. */
    private static Double distanceKm(AllBranch b, Double lat, Double lng) {
        if (lat == null || lng == null || b.getLatitude() == null || b.getLongitude() == null) return null;
        double km = EcomPharmacyService.haversineKm(
                b.getLatitude().doubleValue(), b.getLongitude().doubleValue(), lat, lng);
        return Math.round(km * 10.0) / 10.0;
    }

    private static BigDecimal zeroIfNull(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }

    private static BigDecimal round(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    private static String plain(BigDecimal v) {
        return round(v).stripTrailingZeros().toPlainString();
    }
}
