package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * What a coupon code is worth on one basket (docs/ecommerce Phase 12).
 *
 * <p>Asked twice, and the second answer is the one that counts: once while the
 * customer shops, so the cart can show the saving, and again inside the
 * transaction that writes the invoice. Between those two moments a code can
 * expire, run out, or stop applying because the basket shrank — so the cart's
 * figure is a quote and the checkout's is the answer, exactly as prices and
 * stock work in this module already.
 *
 * <p><b>Nothing is trusted from the client but the code itself.</b> The amount,
 * the rules and the basket all come from the server.
 *
 * <p><b>Why the refusals are sentences.</b> A customer who types a code and is
 * told "invalid" learns nothing; one who is told "this code is for wholesalers"
 * or "add ৳400 more" knows what to do next. Every refusal below says which rule
 * it failed and, where it can, what would fix it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomCouponService {

    /** What a code is worth here, or why it is worth nothing. */
    @Getter @Builder
    public static class Quote {
        private final boolean valid;
        private final Long couponId;
        private final String code;
        private final String name;
        /** Money off the goods. Zero for a free-delivery coupon, which pays the journey instead. */
        private final BigDecimal discount;
        /** True when this coupon covers the delivery charge. */
        private final boolean freeDelivery;
        /** What to tell the customer — a saving, or the reason there is none. */
        private final String message;
    }

    private final EcomCouponRepository coupons;
    private final EcomCouponBranchRepository couponBranches;
    private final EcomCouponUsageRepository usages;
    private final EcomOrderRepository orders;

    /* ══════════════════════════════════════════════════════════════════════
       VALUING A CODE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * What this code is worth to this customer, at this pharmacy, on this basket.
     *
     * @param lock true at the moment of redemption, when the coupon row is held
     *             while its limits are counted and the usage written
     */
    @Transactional(readOnly = true)
    public Quote quote(String code, Long customerId, String customerTier, Long branchId,
                       BigDecimal subtotal, BigDecimal deliveryCharge, boolean lock) {
        String typed = code == null ? "" : code.trim();
        if (typed.isEmpty()) return refuse(null, "Type a coupon code.");

        EcomCoupon c = (lock ? coupons.lockByCode(typed) : coupons.findByCode(typed)).orElse(null);
        if (c == null) return refuse(typed, "No coupon with that code.");

        if (!c.isLive() || !"Y".equals(c.getStatus())) {
            return refuse(typed, "That coupon is no longer being offered.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (c.getStartsAt() != null && now.isBefore(c.getStartsAt())) {
            return refuse(typed, "That coupon is not open yet — it starts on "
                    + c.getStartsAt().toLocalDate() + ".");
        }
        if (c.getEndsAt() != null && now.isAfter(c.getEndsAt())) {
            return refuse(typed, "That coupon expired on " + c.getEndsAt().toLocalDate() + ".");
        }

        /* Where it may be spent. The order's branch decides this, never the
           customer's registration branch: a global customer has no home
           pharmacy, and the goods come off one shelf. */
        if (EcomCoupon.SELECTED_BRANCHES.equals(c.getBranchScope())
                && !couponBranches.existsByPidCouponAndPidBranch(c.getPidCoupon(), branchId)) {
            return refuse(typed, "That coupon is not valid at this pharmacy.");
        }

        if (!EcomCoupon.TIER_ALL.equals(c.getCustomerTypeScope())
                && !c.getCustomerTypeScope().equalsIgnoreCase(customerTier)) {
            return refuse(typed, "That coupon is for " + c.getCustomerTypeScope().toLowerCase()
                    + " accounts, and yours is " + String.valueOf(customerTier).toLowerCase() + ".");
        }

        BigDecimal goods = subtotal == null ? BigDecimal.ZERO : subtotal;
        if (c.getMinOrderAmount() != null && goods.compareTo(c.getMinOrderAmount()) < 0) {
            return refuse(typed, "That coupon needs an order of " + plain(c.getMinOrderAmount())
                    + " or more. Add " + plain(c.getMinOrderAmount().subtract(goods)) + " more.");
        }

        if (c.isFirstOrderOnly() && orders.existsByPidEcomCustomer(customerId)) {
            return refuse(typed, "That coupon is for a first order, and you have ordered before.");
        }

        if (c.getUsageLimitTotal() != null && usages.countLive(c.getPidCoupon()) >= c.getUsageLimitTotal()) {
            return refuse(typed, "That coupon has been fully taken up.");
        }
        if (c.getUsageLimitPerCustomer() != null
                && usages.countLiveForCustomer(c.getPidCoupon(), customerId) >= c.getUsageLimitPerCustomer()) {
            return refuse(typed, c.getUsageLimitPerCustomer() == 1
                    ? "You have already used that coupon."
                    : "You have used that coupon " + c.getUsageLimitPerCustomer() + " times already.");
        }

        /* What it is worth. A percentage is capped by its own ceiling and by the
           basket: a discount larger than the goods is a refund, not a coupon. */
        BigDecimal delivery = deliveryCharge == null ? BigDecimal.ZERO : deliveryCharge;
        boolean freeDelivery = EcomCoupon.FREE_DELIVERY.equals(c.getDiscountType());
        BigDecimal discount = BigDecimal.ZERO;

        if (EcomCoupon.PERCENT.equals(c.getDiscountType())) {
            discount = goods.multiply(c.getDiscountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (c.getMaxDiscountAmount() != null && discount.compareTo(c.getMaxDiscountAmount()) > 0) {
                discount = c.getMaxDiscountAmount();
            }
        } else if (EcomCoupon.FIXED.equals(c.getDiscountType())) {
            discount = c.getDiscountValue();
        }
        discount = discount.min(goods).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        if (freeDelivery && delivery.signum() == 0) {
            return refuse(typed, "That coupon pays for delivery, and this order is already delivered free.");
        }
        if (!freeDelivery && discount.signum() == 0) {
            return refuse(typed, "That coupon is worth nothing on this order.");
        }

        return Quote.builder()
                .valid(true).couponId(c.getPidCoupon()).code(c.getCode()).name(c.getCouponName())
                .discount(discount).freeDelivery(freeDelivery)
                .message(freeDelivery
                        ? c.getCouponName() + " — delivery is on the pharmacy"
                        : c.getCouponName() + " — " + plain(discount) + " off")
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       SPENDING IT
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Writes down that the coupon was spent on this order.
     *
     * <p>Called inside the checkout transaction, after the quote was taken with
     * the coupon row locked. The unique index on the order is the last word: a
     * coupon cannot be recorded twice against the same order however many
     * requests arrive.
     */
    @Transactional
    public void spend(Quote quote, Long customerId, Long orderId, Long branchId) {
        if (quote == null || !quote.isValid()) return;
        usages.save(EcomCouponUsage.builder()
                .pidCoupon(quote.getCouponId())
                .pidEcomCustomer(customerId)
                .pidEcomOrder(orderId)
                .pidBranch(branchId)
                .amount(quote.getDiscount() == null ? BigDecimal.ZERO : quote.getDiscount())
                .usedAt(LocalDateTime.now())
                .build());
        log.info("Coupon {} spent on order {} ({})", quote.getCode(), orderId, quote.getDiscount());
    }

    /**
     * Gives the use back when the order is called off.
     *
     * <p>Released rather than deleted: the fact that it was used and then
     * cancelled is part of what a campaign's numbers mean.
     */
    @Transactional
    public void release(Long orderId, String why) {
        usages.findByPidEcomOrder(orderId).ifPresent(u -> {
            if (u.getReleasedAt() != null) return;
            u.setReleasedAt(LocalDateTime.now());
            usages.save(u);
            log.info("Coupon use on order {} released ({})", orderId, why);
        });
    }

    /** The coupon a cart is holding, for the screens that show it. */
    @Transactional(readOnly = true)
    public EcomCoupon byCode(String code) {
        return coupons.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new BusinessException("No coupon with that code.",
                        HttpStatus.NOT_FOUND, "ECOM_COUPON_NOT_FOUND"));
    }

    private static Quote refuse(String code, String why) {
        return Quote.builder().valid(false).code(code).discount(BigDecimal.ZERO).message(why).build();
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
