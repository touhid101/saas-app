package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.entity.EcomOrder;
import com.friendspharma.app.entity.EcomPointLedger;
import com.friendspharma.app.entity.EcomPointRule;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.repository.EcomPointLedgerRepository;
import com.friendspharma.app.repository.EcomPointRuleRepository;
import com.friendspharma.app.security.SecurityUtils;
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
 * Loyalty points (docs/ecommerce Phase 13).
 *
 * <p><b>The ledger is the truth.</b> Every movement is a row in
 * {@code ECOM_POINT_LEDGER} carrying the balance it produced;
 * {@code ECOM_CUSTOMER.POINTS_BALANCE} is a cache written in the same
 * transaction and never on its own. The ERP treats stock and accounts this way
 * and for the same reason: a counter that drifts is a counter nobody can
 * reconstruct.
 *
 * <p><b>No rule, no scheme.</b> There is no default earn rate and no default
 * exchange rate. Until somebody writes a rule, earning gives nothing and
 * redemption is refused — inventing what a point is worth would be giving away
 * money on somebody else's behalf.
 *
 * <p><b>Earned on delivery, not on ordering.</b> A cancelled order must not
 * leave points behind, and the cheapest way to guarantee that is to pay them
 * when the goods actually arrive. Redeemed points, by contrast, are taken at
 * checkout — they are money off that invoice — and given back if the order is
 * called off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomPointsService {

    /** What a redemption is worth, and why it may be less than asked for. */
    @Getter @Builder
    public static class RedeemQuote {
        private final boolean valid;
        private final Long ruleId;
        /** Points actually redeemable — never more than the balance, the cap, or the bill. */
        private final int points;
        /** What those points take off the bill. */
        private final BigDecimal value;
        private final String message;
    }

    /** What an order would earn, for the line that says so before it is placed. */
    @Getter @Builder
    public static class EarnQuote {
        private final Long ruleId;
        private final int points;
        private final String message;
    }

    private final EcomPointRuleRepository rules;
    private final EcomPointLedgerRepository ledger;
    private final EcomCustomerRepository customers;
    private final EcomAuditRecorder audit;

    /* ══════════════════════════════════════════════════════════════════════
       WHAT APPLIES HERE
       ══════════════════════════════════════════════════════════════════════ */

    /** The rule in force at this pharmacy for this kind of customer, or none. */
    @Transactional(readOnly = true)
    public EcomPointRule ruleFor(Long branchId, String tier) {
        return rules.applicable(branchId, tier == null ? "ALL" : tier, LocalDateTime.now())
                .stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal balanceOf(Long customerId) {
        return ledger.balanceOf(customerId).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * What this order would earn, if it arrives.
     *
     * <p>Points are earned on what the customer pays for goods — not on the
     * delivery charge, which is a journey rather than a purchase, and not on
     * the part a coupon or earlier points already paid for.
     */
    @Transactional(readOnly = true)
    public EarnQuote earnQuote(Long branchId, String tier, BigDecimal payableForGoods) {
        EcomPointRule r = ruleFor(branchId, tier);
        if (r == null || payableForGoods == null) {
            return EarnQuote.builder().points(0).build();
        }
        if (r.getMinOrderToEarn() != null && payableForGoods.compareTo(r.getMinOrderToEarn()) < 0) {
            return EarnQuote.builder().ruleId(r.getPidPointRule()).points(0)
                    .message("Orders over " + plain(r.getMinOrderToEarn()) + " earn points.")
                    .build();
        }
        int points = payableForGoods.multiply(r.getEarnPointsPer100())
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue();
        return EarnQuote.builder()
                .ruleId(r.getPidPointRule()).points(points)
                .message(points > 0 ? "You will earn " + points + " point" + (points == 1 ? "" : "s")
                                      + " when this order arrives" : null)
                .build();
    }

    /**
     * What the customer may spend here, and what it is worth.
     *
     * @param wanted how many points they asked to use; null or 0 means "as many
     *               as the rules allow", which is what a "use my points" button
     *               means
     */
    @Transactional(readOnly = true)
    public RedeemQuote redeemQuote(Long customerId, Long branchId, String tier,
                                   BigDecimal billForGoods, Integer wanted) {
        EcomPointRule r = ruleFor(branchId, tier);
        if (r == null) {
            return refuse("This pharmacy does not take points yet.");
        }
        BigDecimal balance = balanceOf(customerId);
        if (balance.signum() <= 0) return refuse("You have no points to spend.");

        if (r.getMinPointsToRedeem() != null && balance.intValue() < r.getMinPointsToRedeem()) {
            return refuse("You need " + r.getMinPointsToRedeem() + " points to start spending them, and you have "
                    + balance.intValue() + ".");
        }

        /* The most these points may pay for, in money: the cap on the bill, and
           the bill itself. Points cannot buy more than the order is worth. */
        BigDecimal bill = billForGoods == null ? BigDecimal.ZERO : billForGoods;
        BigDecimal maxValue = r.getMaxRedeemPercent() == null ? bill
                : bill.multiply(r.getMaxRedeemPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
        maxValue = maxValue.min(bill);
        if (maxValue.signum() <= 0) return refuse("There is nothing left for points to pay for.");

        int affordable = maxValue.divide(r.getPointValue(), 0, RoundingMode.DOWN).intValue();
        int usable = Math.min(balance.intValue(), affordable);
        if (wanted != null && wanted > 0) usable = Math.min(usable, wanted);

        if (r.getMinPointsToRedeem() != null && usable < r.getMinPointsToRedeem()) {
            return refuse("Points are spent " + r.getMinPointsToRedeem() + " at a time, and this order can only take "
                    + usable + ".");
        }
        if (usable <= 0) return refuse("Your points cannot be used on this order.");

        BigDecimal value = r.getPointValue().multiply(BigDecimal.valueOf(usable)).setScale(2, RoundingMode.DOWN);
        if (value.signum() <= 0) return refuse("Your points are not worth anything on this order yet.");

        return RedeemQuote.builder()
                .valid(true).ruleId(r.getPidPointRule()).points(usable).value(value)
                .message(usable + " point" + (usable == 1 ? "" : "s") + " — " + plain(value) + " off")
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       MOVEMENTS
       ══════════════════════════════════════════════════════════════════════ */

    /** Takes the points, at checkout, against the order they are paying for. */
    @Transactional
    public void spend(RedeemQuote quote, Long customerId, Long orderId, Long branchId, String orderNo) {
        if (quote == null || !quote.isValid() || quote.getPoints() <= 0) return;
        write(customerId, EcomPointLedger.REDEEM, BigDecimal.valueOf(-quote.getPoints()),
                quote.getValue(), orderId, branchId, quote.getRuleId(),
                "Spent on order " + orderNo);
    }

    /**
     * Pays the points an arrived order earned.
     *
     * <p>Idempotent by construction: the partial unique index allows one EARN
     * row per order, so a stage corrected or an event replayed pays once.
     */
    @Transactional
    public void creditOnDelivery(EcomOrder order, String tier) {
        if (order == null || order.getPidEcomOrder() == null) return;
        if (ledger.existsByPidEcomOrderAndEntryType(order.getPidEcomOrder(), EcomPointLedger.EARN)) return;

        /* What they paid for goods: the grand total less the delivery charge —
           a journey is not a purchase — and less nothing else, because the
           coupon and any points already came off that total. */
        BigDecimal goods = nvl(order.getGrandTotal()).subtract(nvl(order.getDeliveryCharge())).max(BigDecimal.ZERO);
        EarnQuote q = earnQuote(order.getPidBranch(), tier, goods);
        if (q.getPoints() <= 0) return;

        write(order.getPidEcomCustomer(), EcomPointLedger.EARN, BigDecimal.valueOf(q.getPoints()),
                null, order.getPidEcomOrder(), order.getPidBranch(), q.getRuleId(),
                "Earned on order " + order.getOrderNo());
        log.info("Order {} earned {} points for customer {}", order.getOrderNo(), q.getPoints(), order.getPidEcomCustomer());
    }

    /**
     * Gives back what a cancelled order took.
     *
     * <p>Written as an ADJUST rather than by deleting the REDEEM: the ledger is
     * append-only, and "spent, then given back" is a truer account than a
     * redemption that never appears to have happened.
     */
    @Transactional
    public void refundOnCancel(Long orderId, String orderNo) {
        ledger.findByPidEcomOrderAndEntryType(orderId, EcomPointLedger.REDEEM).ifPresent(spent -> {
            if (ledger.existsByPidEcomOrderAndEntryType(orderId, EcomPointLedger.ADJUST)) return;
            BigDecimal back = spent.getPoints().abs();
            write(spent.getPidEcomCustomer(), EcomPointLedger.ADJUST, back,
                    null, orderId, spent.getPidBranch(), spent.getPidPointRule(),
                    "Returned when order " + orderNo + " was cancelled");
            log.info("Order {} cancelled: {} points returned", orderNo, back);
        });
    }

    /** A person moving somebody's balance by hand, with a reason. */
    @Transactional
    public BigDecimal adjust(Long customerId, BigDecimal points, String reason) {
        if (points == null || points.signum() == 0) {
            throw new BusinessException("Say how many points to add or take away.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_ZERO");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("An adjustment needs a reason.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_NO_REASON");
        }
        BigDecimal balance = balanceOf(customerId);
        if (balance.add(points).signum() < 0) {
            throw new BusinessException("That would leave the balance below zero — it is "
                    + balance.intValue() + " now.", HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_POINTS_NEGATIVE");
        }
        write(customerId, EcomPointLedger.ADJUST, points, null, null, null, null, reason.trim());
        audit.record("POINTS_ADJUSTED", customerId, null, "ECOM_CUSTOMER", customerId,
                plain(points) + " points · " + reason.trim());
        return balanceOf(customerId);
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE ONE PLACE A BALANCE CHANGES
       ══════════════════════════════════════════════════════════════════════ */

    private void write(Long customerId, String type, BigDecimal points, BigDecimal value,
                       Long orderId, Long branchId, Long ruleId, String note) {
        BigDecimal after = ledger.balanceOf(customerId).add(points).setScale(2, RoundingMode.HALF_UP);
        if (after.signum() < 0) {
            throw new BusinessException("That would spend points the customer does not have.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_POINTS_NEGATIVE");
        }

        EcomPointLedger row = EcomPointLedger.builder()
                .pidEcomCustomer(customerId).entryType(type)
                .points(points.setScale(2, RoundingMode.HALF_UP)).balanceAfter(after)
                .valueAmount(value).pidEcomOrder(orderId).pidBranch(branchId).pidPointRule(ruleId)
                .note(note).createdAt(LocalDateTime.now())
                .createdBy(SecurityUtils.getCurrentUsername())
                .build();
        row.setStatus("Y");
        ledger.save(row);

        /* The cache, in the same transaction as the row that justifies it. */
        customers.findById(customerId).ifPresent(c -> {
            c.setPointsBalance(after);
            customers.save(c);
        });
    }

    private static RedeemQuote refuse(String why) {
        return RedeemQuote.builder().valid(false).points(0).value(BigDecimal.ZERO).message(why).build();
    }

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
