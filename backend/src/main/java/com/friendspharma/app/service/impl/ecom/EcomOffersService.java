package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomCatalogueResponses;
import com.friendspharma.app.dto.response.EcomCatalogueResponses.Item;
import com.friendspharma.app.dto.response.EcomCatalogueResponses.UnitOffer;
import com.friendspharma.app.dto.response.EcomOfferResponses;
import com.friendspharma.app.dto.response.EcomOfferResponses.Campaign;
import com.friendspharma.app.dto.response.EcomOfferResponses.Offers;
import com.friendspharma.app.dto.response.EcomOfferResponses.Voucher;
import com.friendspharma.app.entity.EcomCoupon;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.repository.EcomCouponRepository;
import com.friendspharma.app.repository.EcomCouponUsageRepository;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.repository.EcomOrderItemRepository;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What is on offer right now: the campaign with a deadline, the medicines whose
 * price is actually down, and the vouchers this caller could spend.
 *
 * <p>Three rules decide everything here, because a shop window is the easiest
 * place in a system to start lying:
 *
 * <ol>
 *   <li><b>A countdown needs a real deadline.</b> Product discounts in this ERP
 *       ({@code POS_SALES_DISCOUNT}) carry no dates — they are on until someone
 *       turns them off. A coupon does: {@code ENDS_AT} is a real moment somebody
 *       typed. So the countdown belongs to the coupon, and when no live coupon
 *       has an end date there is no countdown, only the savings.</li>
 *   <li><b>A saving is MRP minus the price this caller pays.</b> Never a
 *       percentage chosen to look good, and never against a "was" price nobody
 *       ever charged.</li>
 *   <li><b>Urgency is the pharmacy's own stock.</b> The bar on a card is pieces
 *       in hand against that pharmacy's reorder level. No invented "43 sold of
 *       80" — this system does not know that, so it does not say it.</li>
 * </ol>
 *
 * <p>Nothing new is computed about coupons here: each candidate goes through
 * {@link EcomCouponService#quote} — the same method the cart uses — so a
 * voucher shown on the storefront is one the cart will honour, and the reason
 * an unusable one is unusable is the sentence the cart would print.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomOffersService {

    /** Below this fraction of the reorder level, the card says so. */
    private static final BigDecimal LOW = new BigDecimal("1.00");

    private final EcomCatalogueService catalogue;
    private final EcomCouponService couponService;
    private final EcomCouponRepository coupons;
    private final EcomCouponUsageRepository usages;
    private final EcomCustomerRepository customers;
    private final EcomEligibilityService eligibility;
    private final EcomOrderItemRepository orderItems;

    /** How far back "sold" looks. A week is long enough to be a real number and short enough to still be news. */
    private static final int SOLD_DAYS = 7;
    /** How far back the "recently ordered" feed looks. */
    private static final int RECENT_DAYS = 3;

    /**
     * @param branchId the pharmacy being shopped, or null to look across every
     *                 pharmacy the caller may buy from
     */
    @Transactional(readOnly = true)
    public Offers offers(Long branchId, Double lat, Double lng, int size) {
        EcomCustomerPrincipal p = EcomSecurity.current();
        EcomCustomer customer = p == null ? null : customers.findById(p.customerId()).orElse(null);
        String tier = customer != null ? customer.getCustomerType() : "RETAILER";

        /* A wholesale customer who has not chosen a pharmacy has no honest
           shelf to show, and the catalogue call would be answering for a
           branch nobody picked. The screen asks them to choose instead. */
        if (branchId == null && customer != null && customer.isWholesaleBuyer()) {
            return Offers.builder().needsPharmacy(true).tier(tier)
                    .flash(List.of()).deals(List.of()).vouchers(List.of())
                    .note("Choose your pharmacy to see what it has on offer.")
                    .build();
        }

        EcomCatalogueResponses.Page page =
                catalogue.search(null, branchId, null, true, lat, lng, null, 0, Math.min(Math.max(size, 12), 100));

        /* Sold figures now ride on the catalogue item itself, so every card in
           the storefront gets them and not only the flash rail. Nothing is
           counted twice here. */
        java.util.Set<Long> branchesOnPage = new java.util.LinkedHashSet<>();
        for (Item it : page.getItems()) branchesOnPage.add(it.getBranchId());

        List<Item> discounted = new ArrayList<>();
        for (Item it : page.getItems()) if (saving(it).compareTo(BigDecimal.ZERO) > 0) discounted.add(it);
        discounted.sort(Comparator.comparing(this::discountPct).reversed());

        int cut = Math.min(discounted.size(), 12);
        List<EcomOfferResponses.Deal> flash = new ArrayList<>();
        for (Item it : discounted.subList(0, cut)) flash.add(deal(it));
        List<EcomOfferResponses.Deal> rest = new ArrayList<>();
        for (Item it : discounted.subList(cut, discounted.size())) rest.add(deal(it));

        List<Voucher> vouchers = vouchers(customer, tier, branchId);
        Campaign campaign = campaign(vouchers);

        return Offers.builder()
                .recent(recent(branchesOnPage, page.getItems()))
                .needsPharmacy(false)
                .tier(tier)
                .branchId(branchId)
                .campaign(campaign)
                .flash(flash)
                .deals(rest)
                .vouchers(vouchers)
                .note(note(campaign, flash.size()))
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE CAMPAIGN — only ever a coupon, because only a coupon has an end
       ══════════════════════════════════════════════════════════════════════ */

    private Campaign campaign(List<Voucher> vouchers) {
        Voucher soonest = vouchers.stream()
                .filter(v -> v.isUsable() && v.getEndsAt() != null)
                .min(Comparator.comparing(Voucher::getEndsAt))
                .orElse(null);
        if (soonest == null) return null;

        long secs = Math.max(0, Duration.between(LocalDateTime.now(), soonest.getEndsAt()).getSeconds());
        return Campaign.builder()
                .code(soonest.getCode()).name(soonest.getName())
                .description(soonest.getDescription())
                .headline(soonest.getHeadline())
                .endsAt(soonest.getEndsAt())
                .secondsLeft(secs)
                .redemptionsLeft(soonest.getRedemptionsLeft())
                .minOrderAmount(soonest.getMinOrderAmount())
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       VOUCHERS — listed here, judged by the cart's own rule
       ══════════════════════════════════════════════════════════════════════ */

    private List<Voucher> vouchers(EcomCustomer customer, String tier, Long branchId) {
        List<Voucher> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (EcomCoupon c : coupons.search("Y", "", org.springframework.data.domain.PageRequest.of(0, 60)).getContent()) {
            if (!c.isLive() || !"Y".equals(c.getStatus())) continue;
            if (c.getStartsAt() != null && now.isBefore(c.getStartsAt())) continue;
            if (c.getEndsAt() != null && now.isAfter(c.getEndsAt())) continue;

            /* A voucher for wholesalers is not an offer to a retailer, and
               showing it would be advertising a price they cannot have. */
            if (!EcomCoupon.TIER_ALL.equals(c.getCustomerTypeScope())
                    && !c.getCustomerTypeScope().equalsIgnoreCase(tier)) continue;

            /* The cart's own verdict, asked at an empty basket: a coupon that
               needs a minimum comes back with that sentence, which is exactly
               what the card should say. */
            EcomCouponService.Quote q = branchId == null ? null
                    : couponService.quote(c.getCode(), customer == null ? null : customer.getPidEcomCustomer(),
                                          tier, branchId, BigDecimal.ZERO, BigDecimal.ZERO, false);
            if (q != null && !q.isValid() && isPermanentRefusal(q.getMessage())) continue;

            Long left = null;
            if (c.getUsageLimitTotal() != null) {
                left = Math.max(0, c.getUsageLimitTotal() - usages.countLive(c.getPidCoupon()));
                if (left == 0) continue;
            }

            out.add(Voucher.builder()
                    .code(c.getCode()).name(c.getCouponName()).description(c.getDescription())
                    .headline(headline(c))
                    .discountType(c.getDiscountType()).discountValue(c.getDiscountValue())
                    .maxDiscountAmount(c.getMaxDiscountAmount())
                    .minOrderAmount(c.getMinOrderAmount())
                    .startsAt(c.getStartsAt()).endsAt(c.getEndsAt())
                    .redemptionsLeft(left)
                    .usable(q == null || q.isValid() || !isPermanentRefusal(q.getMessage()))
                    .note(q == null ? null : (q.isValid() ? null : q.getMessage()))
                    .build());
        }
        out.sort(Comparator.comparing((Voucher v) -> v.getEndsAt() == null ? LocalDateTime.MAX : v.getEndsAt()));
        return out;
    }

    /**
     * A refusal the basket cannot fix is a reason to hide the voucher; one it
     * can fix (spend more) is a reason to show it with the sentence attached.
     */
    private boolean isPermanentRefusal(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("not valid at this pharmacy")
                || m.contains("accounts, and yours")
                || m.contains("no longer being offered")
                || m.contains("already used")
                || m.contains("first order");
    }

    private String headline(EcomCoupon c) {
        if (EcomCoupon.FREE_DELIVERY.equals(c.getDiscountType())) return "Free delivery";
        if (EcomCoupon.PERCENT.equals(c.getDiscountType())) {
            String pct = c.getDiscountValue() == null ? "" : c.getDiscountValue().stripTrailingZeros().toPlainString();
            return pct + "% off";
        }
        return c.getDiscountValue() == null ? c.getCouponName()
                : c.getDiscountValue().setScale(0, RoundingMode.DOWN).toPlainString() + " off";
    }

    /* ══════════════════════════════════════════════════════════════════════
       RECENTLY ORDERED
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * What has been bought here lately.
     *
     * <p>Three things are deliberately absent, and all three matter on a
     * pharmacy rather than a general marketplace:
     *
     * <ul>
     *   <li><b>No buyer.</b> The query never selects a customer id, name or
     *       address. A "someone just bought this" feed is otherwise a list of
     *       what people nearby are being treated for.</li>
     *   <li><b>No prescription medicines.</b> Filtered out in the query. What
     *       somebody was prescribed is the most revealing line in the table,
     *       and no shop window needs it.</li>
     *   <li><b>No exact times.</b> "8 hours ago", not a timestamp — a precise
     *       moment plus a small pharmacy narrows a purchase to a person.</li>
     * </ul>
     *
     * <p>The same medicine is shown once however many people bought it, so the
     * feed is a list of what is moving rather than a count of who bought what.
     */
    private List<EcomOfferResponses.RecentOrder> recent(java.util.Set<Long> branches, List<Item> onPage) {
        if (branches.isEmpty()) return List.of();

        java.util.Map<Long, Item> byProduct = new java.util.HashMap<>();
        for (Item i : onPage) byProduct.putIfAbsent(i.getProductId(), i);

        List<EcomOfferResponses.RecentOrder> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();

        for (Object[] row : orderItems.recentLines(branches,
                LocalDateTime.now().minusDays(RECENT_DAYS),
                org.springframework.data.domain.PageRequest.of(0, 60))) {
            Long branchId = (Long) row[0];
            Long productId = (Long) row[1];
            if (productId == null || !seen.add(productId)) continue;

            Item it = byProduct.get(productId);
            if (it == null) continue;               // not on this shelf any more
            UnitOffer u = best(it);
            if (u == null) continue;                // nothing this caller may buy

            LocalDateTime at = (LocalDateTime) row[4];
            long mins = Math.max(1, Duration.between(at, LocalDateTime.now()).toMinutes());

            out.add(EcomOfferResponses.RecentOrder.builder()
                    .productId(productId).branchId(branchId).branchName(it.getBranchName())
                    .name(it.getName()).imageUrl(it.getImageUrl())
                    .price(u.getPrice()).mrp(u.getMrp())
                    .savingPct(discountPct(it).setScale(0, RoundingMode.HALF_UP).intValue())
                    .whenText(ago(mins)).minutesAgo(mins)
                    .inStock(it.isInStock())
                    .stockPcs(it.getAvailablePcs()).unit(u.getUnit())
                    .branchVerified(it.isBranchVerified())
                    .build());
            if (out.size() >= 12) break;
        }
        return out;
    }

    /** Coarse on purpose: a precise time plus a small pharmacy narrows a purchase to a person. */
    private String ago(long mins) {
        if (mins < 60) return mins + (mins == 1 ? " min ago" : " mins ago");
        long h = mins / 60;
        if (h < 24) return h + (h == 1 ? " hr ago" : " hrs ago");
        long d = h / 24;
        return d + (d == 1 ? " day ago" : " days ago");
    }

    /* ══════════════════════════════════════════════════════════════════════
       DEALS
       ══════════════════════════════════════════════════════════════════════ */

    private EcomOfferResponses.Deal deal(Item it) {
        UnitOffer u = best(it);
        BigDecimal stock = it.getAvailablePcs() == null ? BigDecimal.ZERO : it.getAvailablePcs();
        BigDecimal reorder = it.getReorderPcs();

        /* The bar is pieces in hand against the pharmacy's reorder level, and
           it is drawn only when the pharmacy set one. Full bar = comfortably
           stocked; a short bar means this counter is close to reordering. */
        Integer pct = null;
        boolean low = false;
        if (reorder != null && reorder.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = stock.divide(reorder.multiply(new BigDecimal("2")), 4, RoundingMode.HALF_UP);
            pct = ratio.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).intValue();
            if (pct > 100) pct = 100;
            if (pct < 3 && stock.compareTo(BigDecimal.ZERO) > 0) pct = 3;
            low = stock.compareTo(reorder.multiply(LOW)) <= 0;
        }

        /* Both figures come from the item, which the catalogue counted once. */
        return EcomOfferResponses.Deal.builder()
                .item(it)
                .soldPcs(it.getSoldPcs()).soldBarPct(it.getSoldBarPct())
                .unit(u == null ? it.getDefaultUnit() : u.getUnit())
                .price(u == null ? null : u.getPrice())
                .mrp(u == null ? null : u.getMrp())
                .savingPct(discountPct(it).setScale(0, RoundingMode.HALF_UP).intValue())
                .saving(saving(it))
                .stockPcs(stock)
                .reorderPcs(reorder)
                .stockBarPct(pct)
                .runningLow(low)
                .build();
    }

    /** The unit the card quotes: the caller's default when they may buy it, else the cheapest they may. */
    private UnitOffer best(Item it) {
        List<UnitOffer> ok = it.getUnits().stream().filter(u -> u.isAllowed() && u.getPrice() != null).toList();
        if (ok.isEmpty()) return null;
        return ok.stream().filter(u -> u.getUnit().equals(it.getDefaultUnit())).findFirst().orElse(ok.get(0));
    }

    private BigDecimal saving(Item it) {
        UnitOffer u = best(it);
        if (u == null || u.getMrp() == null || u.getPrice() == null) return BigDecimal.ZERO;
        BigDecimal d = u.getMrp().subtract(u.getPrice());
        return d.compareTo(BigDecimal.ZERO) > 0 ? d : BigDecimal.ZERO;
    }

    private BigDecimal discountPct(Item it) {
        UnitOffer u = best(it);
        if (u == null || u.getMrp() == null || u.getMrp().compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return saving(it).multiply(new BigDecimal("100")).divide(u.getMrp(), 2, RoundingMode.HALF_UP);
    }

    private String note(Campaign c, int flash) {
        if (flash == 0) return "Nothing is discounted at this pharmacy right now.";
        if (c == null) return flash + " medicine" + (flash == 1 ? " is" : "s are") + " below their usual price here.";
        return c.getHeadline() + " with " + c.getCode() + ", and " + flash
                + " medicine" + (flash == 1 ? " is" : "s are") + " already below their usual price.";
    }
}
