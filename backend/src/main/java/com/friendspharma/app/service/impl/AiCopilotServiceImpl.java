package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.AiCopilotResponse;
import com.friendspharma.app.dto.response.AiCopilotResponse.*;
import com.friendspharma.app.entity.PosRequisitionDtl;
import com.friendspharma.app.entity.PosRequisitionMst;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.entity.PosSupplierInfo;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AiAnalyticsRepository;
import com.friendspharma.app.repository.PosRequisitionMstRepository;
import com.friendspharma.app.repository.PosSupplierInfoRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The reasoning behind the AI Copilot.
 *
 * <p>There is no model here and the name should not suggest one. It is
 * arithmetic over the stock ledger, stated plainly enough that a buyer can
 * disagree with it: how fast a thing sells, how long the stock will last, what
 * it takes to cover the wait for the next delivery, and what is going to expire
 * before it can be sold. Every recommendation carries the figures it came from
 * so the buyer can check the working rather than trust a number.
 *
 * <p><b>Two judgements are worth spelling out.</b>
 *
 * <p>The first is that cover is measured in <em>sellable</em> stock, not stock.
 * Four hundred pieces with three weeks of shelf life left is not four hundred
 * pieces of cover for a product that moves five a day — it is a hundred and
 * five pieces of cover and a write-off, and a reorder engine that cannot see
 * the difference will let a fast line run out while the report says it is full.
 *
 * <p>The second is that confidence comes from how many <em>days</em> a product
 * sold on, not how many pieces. Thirty pieces spread over twenty days is a
 * rate worth forecasting from. Thirty pieces on a single day was one customer,
 * and treating it as a rate is how a pharmacy ends up with a year of stock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCopilotServiceImpl {

    private final AiAnalyticsRepository repo;
    private final PosRequisitionMstRepository reqRepo;
    private final PosSupplierInfoRepository supplierRepo;
    private final BranchScopeService branchScope;

    /** Below this many selling days in a month, a rate is a guess. */
    private static final int THIN_SELLING_DAYS = 4;
    /** Stock inside this many days of expiry is money already at risk. */
    private static final int EXPIRY_HORIZON_DAYS = 90;
    /** No sale in this long, with stock on the shelf, is dead capital. */
    private static final int DEAD_STOCK_DAYS = 60;

    /* ══════════════════════════════════════════════════════════════════════
       THE BOARD
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Board board(Long branchId, boolean allBranches, Long supplierId, int leadTimeDays,
                       int coverDays, BigDecimal safetyFactor, int limit) {

        Long orgId = SecurityUtils.getCurrentOrgId();
        Long branch = branchScope.resolve(branchId, allBranches);
        Long companyId = companyOf(supplierId);
        List<Signal> signals = signals(orgId, branch, companyId);

        Map<Long, Revenue> revenue = revenue(orgId, branch, 30);
        List<Reorder> reorder = reorder(signals, leadTimeDays, coverDays, safetyFactor, limit);
        List<Mover>   movers  = movers(signals, revenue, limit);

        List<DayPoint> trend = repo.dailySales(orgId, branch, 90).stream()
                .map(r -> DayPoint.builder()
                        .day(date(r[0]))
                        .pcs(dec(r[1]))
                        .build())
                .toList();

        return Board.builder()
                .reorder(reorder)
                .movers(movers)
                .watch(watch(signals))
                .insights(insights(signals, reorder, revenue))
                .trend(trend)
                .summary(summary(signals, reorder, trend))
                .build();
    }

    /**
     * The manufacturer a supplier distributes for, or null for "everyone".
     *
     * <p>A supplier sells one maker's products and nobody else's, so choosing a
     * supplier is the same act as choosing a manufacturer. Doing the resolution
     * here rather than on the client means a caller cannot ask for one
     * supplier's products and be handed another's.
     */
    private Long companyOf(Long supplierId) {
        if (supplierId == null) return null;
        PosSupplierInfo s = supplierRepo.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));
        if (s.getPosPidCompany() == null) {
            throw new ValidationException(
                    s.getSupplierName() + " has no manufacturer on file, so there is no way to "
                    + "tell which products they supply. Set one on the supplier first.");
        }
        return s.getPosPidCompany();
    }

    /* ══════════════════════════════════════════════════════════════════════
       REORDER
       ══════════════════════════════════════════════════════════════════════ */

    private List<Reorder> reorder(List<Signal> signals, int leadTimeDays, int coverDays,
                                  BigDecimal safetyFactor, int limit) {

        List<Reorder> out = new ArrayList<>();

        for (Signal s : signals) {
            // Nothing sells, nothing is stocked — there is no question to answer.
            if (s.perDay.signum() <= 0 && s.stockPcs.signum() <= 0) continue;

            // Cover the wait for the delivery, plus the period it has to last,
            // plus a margin for the weeks that run hot.
            BigDecimal safety = s.perDay
                    .multiply(BigDecimal.valueOf(leadTimeDays))
                    .multiply(safetyFactor.subtract(BigDecimal.ONE).max(BigDecimal.ZERO));
            BigDecimal target = s.perDay
                    .multiply(BigDecimal.valueOf(leadTimeDays + coverDays))
                    .add(safety);

            // Measured against what can still be sold, not against what is on
            // the shelf: see the note on the class.
            BigDecimal deficit = target.subtract(s.sellablePcs).max(BigDecimal.ZERO);

            // A product with a reorder level set is judged against that too —
            // it is the buyer's own instruction and outranks the arithmetic.
            boolean underReorderLevel = s.reorderLevel.signum() > 0
                    && s.stockPcs.compareTo(s.reorderLevel) <= 0;

            boolean dead = isDead(s);
            if (deficit.signum() <= 0 && !underReorderLevel && !dead) continue;
            if (dead) continue;               // dead stock is an insight, not a purchase

            int boxes = deficit.signum() > 0
                    ? Math.max(1, (int) Math.ceil(deficit.doubleValue() / Math.max(s.pcsPerBox, 1)))
                    : 1;

            Integer cover = coverDays(s);
            List<String> reasons = new ArrayList<>();
            if (s.stockPcs.signum() <= 0)                       reasons.add("Out of stock");
            else if (cover != null && cover <= leadTimeDays)    reasons.add("Runs out before delivery");
            if (underReorderLevel)                              reasons.add("Below reorder level");
            if (s.expiringPcs.signum() > 0)                     reasons.add("Short-dated stock");
            if (s.trendPct != null && s.trendPct.compareTo(BigDecimal.valueOf(20)) > 0)
                                                               reasons.add("Selling faster");
            if (s.sellingDays < THIN_SELLING_DAYS && s.perDay.signum() > 0)
                                                               reasons.add("Thin history");
            if (reasons.isEmpty())                             reasons.add("Below target cover");

            out.add(Reorder.builder()
                    .productId(s.productId).productName(s.productName).sku(s.sku)
                    .companyId(s.companyId).companyName(s.companyName).categoryName(s.categoryName)
                    .pcsPerBox(s.pcsPerBox).costPerBox(s.costPerBox).mrpPerBox(s.mrpPerBox)
                    .stockPcs(s.stockPcs).sellablePcs(scale(s.sellablePcs, 0))
                    .velocityPerDay(scale(s.perDay, 2))
                    .daysOfCover(cover)
                    .stockoutOn(cover == null ? null : LocalDate.now().plusDays(cover))
                    .safetyStockPcs(scale(safety, 0)).targetStockPcs(scale(target, 0))
                    .deficitPcs(scale(deficit, 0))
                    .suggestedBoxes(boxes)
                    .estimatedCost(scale(s.costPerBox.multiply(BigDecimal.valueOf(boxes)), 2))
                    .priority(priority(cover, leadTimeDays, s))
                    .confidence(confidence(s))
                    .sellingDays(s.sellingDays)
                    .trendPct(s.trendPct)
                    .nearestExpiry(s.nearestExpiry)
                    .expiringPcs(s.expiringPcs).expiringValue(scale(s.expiringValue, 2))
                    .reasons(reasons)
                    .build());
        }

        out.sort(Comparator
                .comparingInt((Reorder r) -> switch (r.getPriority()) {
                    case "CRITICAL" -> 0; case "HIGH" -> 1; case "MEDIUM" -> 2; default -> 3; })
                .thenComparing(r -> r.getDaysOfCover() == null ? Integer.MAX_VALUE : r.getDaysOfCover()));

        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * How urgent it is, in the buyer's terms rather than a score.
     *
     * <p>The comparison is against the lead time, not a fixed number of days.
     * Seven days of cover is comfortable when the supplier delivers next
     * morning and an emergency when they take a fortnight.
     */
    private String priority(Integer cover, int leadTimeDays, Signal s) {
        if (s.stockPcs.signum() <= 0 && s.perDay.signum() > 0) return "CRITICAL";
        if (cover == null) return "LOW";
        if (cover <= leadTimeDays)          return "CRITICAL";
        if (cover <= leadTimeDays * 2)      return "HIGH";
        if (cover <= leadTimeDays * 4)      return "MEDIUM";
        return "LOW";
    }

    private String confidence(Signal s) {
        if (s.sellingDays >= 15) return "HIGH";
        if (s.sellingDays >= THIN_SELLING_DAYS) return "MEDIUM";
        return "LOW";
    }

    /* ══════════════════════════════════════════════════════════════════════
       MOVERS
       ══════════════════════════════════════════════════════════════════════ */

    private List<Mover> movers(List<Signal> signals, Map<Long, Revenue> revenue, int limit) {
        List<Mover> out = new ArrayList<>();

        for (Signal s : signals) {
            Revenue rev = revenue.getOrDefault(s.productId, Revenue.EMPTY);
            if (rev.revenue.signum() <= 0 && s.stockPcs.signum() <= 0) continue;

            BigDecimal margin = rev.revenue.subtract(rev.cost);
            Integer cover = coverDays(s);

            out.add(Mover.builder()
                    .productId(s.productId).productName(s.productName).companyName(s.companyName)
                    .revenue(scale(rev.revenue, 2)).margin(scale(margin, 2))
                    .marginPct(rev.revenue.signum() > 0
                            ? scale(margin.multiply(BigDecimal.valueOf(100))
                                    .divide(rev.revenue, 4, RoundingMode.HALF_UP), 1)
                            : BigDecimal.ZERO)
                    .pcs(scale(rev.pcs, 0)).invoices(rev.invoices)
                    .velocityPerDay(scale(s.perDay, 2)).trendPct(s.trendPct)
                    .stockPcs(s.stockPcs).daysOfCover(cover)
                    .momentum(momentum(s))
                    .stockSignal(stockSignal(s, cover))
                    .build());
        }

        out.sort(Comparator.comparing(Mover::getRevenue).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private String momentum(Signal s) {
        if (s.perDay.signum() <= 0) return "STALLED";
        if (s.trendPct == null)     return "STEADY";
        if (s.trendPct.compareTo(BigDecimal.valueOf(15))  > 0) return "RISING";
        if (s.trendPct.compareTo(BigDecimal.valueOf(-15)) < 0) return "SLIPPING";
        return "STEADY";
    }

    private String stockSignal(Signal s, Integer cover) {
        if (isDead(s))                    return "DEAD";
        if (s.stockPcs.signum() <= 0)     return "OUT";
        if (cover == null)                return "OK";
        if (cover <= 7)                   return "CRITICAL";
        if (cover <= 21)                  return "LOW";
        return "OK";
    }

    /* ══════════════════════════════════════════════════════════════════════
       WATCH — dated or stalled stock
       ══════════════════════════════════════════════════════════════════════ */

    private List<Watch> watch(List<Signal> signals) {
        List<Watch> out = new ArrayList<>();

        for (Signal s : signals) {
            boolean dead = isDead(s);
            boolean expiring = s.expiringPcs.signum() > 0;
            if (!dead && !expiring) continue;

            // The write-off is the part that cannot sell in time, not the whole
            // dated batch: a fast line clears its short-dated stock and loses
            // nothing, and calling that a risk would cry wolf every week.
            //
            // Capped at the stock that is actually dated. A product that has
            // stopped selling has all of its stock "unsellable" by the velocity
            // maths, but if the expiry is two years out that is idle capital,
            // not a write-off, and the two want different decisions.
            BigDecimal atRiskPcs = s.stockPcs.subtract(s.sellablePcs).max(BigDecimal.ZERO)
                    .min(s.expiringPcs);
            Integer daysToExpiry = s.nearestExpiry == null ? null
                    : (int) ChronoUnit.DAYS.between(LocalDate.now(), s.nearestExpiry);
            Integer idle = s.lastSold == null ? null
                    : (int) ChronoUnit.DAYS.between(s.lastSold, LocalDate.now());

            String kind = dead && expiring ? "BOTH" : dead ? "DEAD" : "EXPIRING";
            String suggestion;
            if (dead && expiring) {
                suggestion = "Not sold in months and dated — return it to the supplier or write it off now.";
            } else if (dead) {
                suggestion = "Discount it or return it; do not reorder.";
            } else if (atRiskPcs.signum() > 0) {
                suggestion = "Will not clear at the current rate — discount it or move it to another branch.";
            } else {
                suggestion = "Dated, but it should clear in time. Worth watching, not acting on.";
            }

            out.add(Watch.builder()
                    .productId(s.productId).productName(s.productName).companyName(s.companyName)
                    .stockPcs(s.stockPcs).sellablePcs(scale(s.sellablePcs, 0))
                    .atRiskPcs(scale(atRiskPcs, 0))
                    .atRiskValue(scale(atRiskPcs.multiply(s.costPerPcs()), 2))
                    .stockValue(scale(s.stockValue, 2))
                    .nearestExpiry(s.nearestExpiry).daysToExpiry(daysToExpiry)
                    .lastSold(s.lastSold).idleDays(idle)
                    .velocityPerDay(scale(s.perDay, 2))
                    .kind(kind).suggestion(suggestion)
                    .build());
        }

        // Worst money first: what is at risk, then what is simply idle.
        out.sort(Comparator.comparing(Watch::getAtRiskValue).reversed()
                 .thenComparing(Watch::getStockValue, Comparator.reverseOrder()));
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       INSIGHTS
       ══════════════════════════════════════════════════════════════════════ */

    private List<Insight> insights(List<Signal> signals, List<Reorder> reorder,
                                   Map<Long, Revenue> revenue) {
        List<Insight> out = new ArrayList<>();

        long outOfStock = signals.stream()
                .filter(s -> s.stockPcs.signum() <= 0 && s.perDay.signum() > 0).count();
        if (outOfStock > 0) {
            BigDecimal lost = signals.stream()
                    .filter(s -> s.stockPcs.signum() <= 0 && s.perDay.signum() > 0)
                    .map(s -> s.perDay.multiply(s.mrpPerPcs()).multiply(BigDecimal.valueOf(30)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(Insight.builder().key("OUT_OF_STOCK").severity("CRITICAL")
                    .title(outOfStock + " product" + (outOfStock == 1 ? "" : "s") + " out of stock")
                    .detail("These were selling and are now unavailable. At the rate they were "
                            + "going, that is roughly what a month without them costs in sales.")
                    .amount(scale(lost, 2)).count((int) outOfStock).action("reorder").build());
        }

        long critical = reorder.stream().filter(r -> "CRITICAL".equals(r.getPriority())).count();
        if (critical > 0) {
            BigDecimal spend = reorder.stream().filter(r -> "CRITICAL".equals(r.getPriority()))
                    .map(Reorder::getEstimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(Insight.builder().key("CRITICAL_REORDER").severity("CRITICAL")
                    .title(critical + " line" + (critical == 1 ? "" : "s") + " will run out before a delivery arrives")
                    .detail("Ordered today, the stock still runs down before it can be replaced. "
                            + "These are the ones to place first.")
                    .amount(scale(spend, 2)).count((int) critical).action("reorder").build());
        }

        List<Signal> expiring = signals.stream()
                .filter(s -> s.expiringPcs.signum() > 0).toList();
        if (!expiring.isEmpty()) {
            BigDecimal value = expiring.stream().map(s -> s.expiringValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // What will not sell in time, rather than everything that is dated.
            BigDecimal atRisk = expiring.stream()
                    .map(s -> s.stockPcs.subtract(s.sellablePcs).max(BigDecimal.ZERO)
                              .multiply(s.costPerPcs()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(Insight.builder().key("EXPIRY").severity(atRisk.signum() > 0 ? "WARNING" : "INFO")
                    .title(expiring.size() + " product" + (expiring.size() == 1 ? "" : "s")
                           + " holding short-dated stock")
                    .detail(scale(value, 2) + " expires within " + EXPIRY_HORIZON_DAYS + " days, of which "
                            + scale(atRisk, 2) + " will not sell in time at the current rate. "
                            + "Discount or return it while it still has value.")
                    .amount(scale(atRisk, 2)).count(expiring.size()).action("expiry").build());
        }

        List<Signal> dead = signals.stream().filter(this::isDead).toList();
        if (!dead.isEmpty()) {
            BigDecimal tied = dead.stream().map(s -> s.stockValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(Insight.builder().key("DEAD_STOCK").severity("WARNING")
                    .title(dead.size() + " product" + (dead.size() == 1 ? "" : "s") + " have not sold in "
                           + DEAD_STOCK_DAYS + " days")
                    .detail("Capital sitting on a shelf. Worth clearing before it dates, and worth "
                            + "not reordering.")
                    .amount(scale(tied, 2)).count(dead.size()).action("expiry").build());
        }

        BigDecimal totalRevenue = revenue.values().stream().map(r -> r.revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalMargin = revenue.values().stream().map(r -> r.revenue.subtract(r.cost))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalRevenue.signum() > 0) {
            BigDecimal pct = totalMargin.multiply(BigDecimal.valueOf(100))
                    .divide(totalRevenue, 1, RoundingMode.HALF_UP);
            out.add(Insight.builder().key("MARGIN")
                    .severity(pct.compareTo(BigDecimal.valueOf(15)) < 0 ? "WARNING" : "GOOD")
                    .title("Gross margin " + pct + "% over 30 days")
                    .detail("Measured against the cost of the batches that actually went out, not "
                            + "today's purchase price.")
                    .amount(scale(totalMargin, 2)).count(null).action("movers").build());
        }

        return out;
    }

    private Summary summary(List<Signal> signals, List<Reorder> reorder, List<DayPoint> trend) {
        BigDecimal stockValue = signals.stream().map(s -> s.stockValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expiring = signals.stream().map(s -> s.expiringValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deadValue = signals.stream().filter(this::isDead).map(s -> s.stockValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int sellingDays = (int) trend.stream().filter(d -> d.getPcs().signum() > 0).count();

        return Summary.builder()
                .productsTracked(signals.size())
                .needAttention(reorder.size())
                .suggestedSpend(scale(reorder.stream().map(Reorder::getEstimatedCost)
                        .reduce(BigDecimal.ZERO, BigDecimal::add), 2))
                .stockValue(scale(stockValue, 2))
                .expiringValue(scale(expiring, 2))
                .deadStockValue(scale(deadValue, 2))
                .outOfStock((int) signals.stream()
                        .filter(s -> s.stockPcs.signum() <= 0 && s.perDay.signum() > 0).count())
                .historyDays(sellingDays)
                // Under a fortnight of trading, a thirty-day rate is mostly noise
                // and the screen says so rather than pretending otherwise.
                .thinHistory(sellingDays < 14)
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       ASK
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Answers a typed question from the same figures the board is built from.
     *
     * <p>Deliberately not a language model. The questions a buyer actually asks
     * have exact answers sitting in the ledger, and a generated paragraph that
     * is right most of the time is worse than a short list that is right every
     * time — this is what someone will place an order against.
     */
    @Transactional(readOnly = true)
    public Answer ask(String question, Long branchId, boolean allBranches, Long supplierId,
                      int leadTimeDays, int coverDays, BigDecimal safetyFactor) {

        Board b = board(branchId, allBranches, supplierId, leadTimeDays, coverDays, safetyFactor, 100);
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);

        // Checked before the movers question: "what is not selling" contains
        // "selling", and answering it with a list of best sellers is the exact
        // opposite of what was asked.
        if (contains(q, "dead", "not sold", "not selling", "no sales", "idle", "slow",
                        "stuck", "clear", "stopped")) {
            Insight dead = find(b, "DEAD_STOCK");
            List<String> lines = b.getWatch().stream()
                    .filter(w -> !"EXPIRING".equals(w.getKind()))
                    .limit(6)
                    .map(w -> w.getProductName() + " — " + plain(w.getStockPcs()) + " pcs on the shelf, "
                              + (w.getIdleDays() == null ? "never sold"
                                                         : "nothing sold in " + w.getIdleDays() + " days")
                              + " (" + w.getStockValue() + " tied up)")
                    .toList();
            return Answer.builder().question(question)
                    .headline(dead != null ? dead.getTitle() : "Nothing is sitting idle.")
                    .bullets(lines).action("watch").build();
        }

        if (contains(q, "expire", "expiry", "short-dated", "shortdated", "expiring")) {
            List<String> lines = b.getWatch().stream()
                    .filter(w -> !"DEAD".equals(w.getKind()))
                    .sorted(Comparator.comparing(Watch::getNearestExpiry,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(6)
                    .map(w -> w.getProductName() + " — " + plain(w.getStockPcs()) + " pcs, earliest "
                              + w.getNearestExpiry() + " (" + w.getDaysToExpiry() + " days); "
                              + (w.getAtRiskPcs().signum() > 0
                                 ? plain(w.getAtRiskPcs()) + " pcs will not clear, " + w.getAtRiskValue() + " at risk"
                                 : "should clear in time"))
                    .toList();
            Insight exp = find(b, "EXPIRY");
            return Answer.builder().question(question)
                    .headline(exp != null ? exp.getTitle() : "Nothing is close to expiring.")
                    .bullets(lines).action("watch").build();
        }

        if (contains(q, "hot", "best", "top", "selling", "revenue", "margin", "profit")) {
            List<String> lines = b.getMovers().stream().limit(5)
                    .map(m -> m.getProductName() + " — " + m.getRevenue() + " revenue, "
                              + m.getMarginPct() + "% margin, " + m.getMomentum().toLowerCase(Locale.ROOT))
                    .toList();
            return Answer.builder().question(question)
                    .headline("Top " + lines.size() + " by revenue over 30 days")
                    .bullets(lines).action("movers").build();
        }

        if (contains(q, "run out", "stockout", "stock out", "first", "urgent", "when")) {
            List<String> lines = b.getReorder().stream()
                    .filter(r -> r.getDaysOfCover() != null)
                    .sorted(Comparator.comparingInt(Reorder::getDaysOfCover))
                    .limit(6)
                    .map(r -> r.getProductName() + " — " + r.getDaysOfCover() + " day"
                              + (r.getDaysOfCover() == 1 ? "" : "s") + " left, out around " + r.getStockoutOn())
                    .toList();
            return Answer.builder().question(question)
                    .headline(lines.isEmpty() ? "Nothing is close to running out."
                                              : "Closest to running out")
                    .bullets(lines).action("reorder").build();
        }

        // "What should I buy?" — the default, because it is the reason the page exists.
        List<String> lines = b.getReorder().stream().limit(6)
                .map(r -> r.getProductName() + " — " + r.getSuggestedBoxes() + " box"
                          + (r.getSuggestedBoxes() == 1 ? "" : "es") + " at about " + r.getEstimatedCost()
                          + " (" + r.getPriority().toLowerCase(Locale.ROOT) + ")")
                .toList();
        return Answer.builder().question(question)
                .headline(lines.isEmpty()
                        ? "Nothing needs ordering — every line has cover."
                        : b.getSummary().getNeedAttention() + " lines need ordering, about "
                          + b.getSummary().getSuggestedSpend() + " in total")
                .bullets(lines).action("reorder").build();
    }

    private static Insight find(Board b, String key) {
        return b.getInsights().stream().filter(i -> key.equals(i.getKey())).findFirst().orElse(null);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static boolean contains(String q, String... needles) {
        for (String n : needles) if (q.contains(n)) return true;
        return false;
    }

    /* ══════════════════════════════════════════════════════════════════════
       REQUISITION
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Turns chosen recommendations into a requisition.
     *
     * <p>The quantities the buyer settled on are what gets written, and the
     * recommendation is kept beside them in AI_RECOMMENDED_QTY. When the order
     * lands short six months later, the question asked is always whether the
     * suggestion or the override was wrong, and only keeping both can answer it.
     */
    @Transactional
    public Map<String, Object> createRequisition(Map<Long, Integer> boxesByProduct, String remarks,
                                                 Long branchId, boolean allBranches, Long supplierId,
                                                 int leadTimeDays, int coverDays,
                                                 BigDecimal safetyFactor) {
        if (boxesByProduct == null || boxesByProduct.isEmpty()) {
            throw new ValidationException("Pick at least one product to order.");
        }
        // A requisition is raised for one branch — the goods are delivered
        // somewhere. Quantities worked out across every branch at once do not
        // belong to any of them, so the branch has to be named before this can
        // be turned into an order.
        if (allBranches) {
            throw new ValidationException(
                    "Choose a branch before raising a requisition. The figures currently cover "
                    + "every branch at once, and an order has to be for one of them.");
        }

        Long orgId = SecurityUtils.getCurrentOrgId();
        Long branch = branchScope.resolve(branchId, false);
        Long companyId = companyOf(supplierId);
        // Resolved against every product, not just the supplier's, so a line
        // belonging to somebody else can be named in the refusal rather than
        // silently vanishing from the list.
        Map<Long, Reorder> byId = reorder(signals(orgId, branch, null), leadTimeDays, coverDays,
                                          safetyFactor, Integer.MAX_VALUE)
                .stream().collect(Collectors.toMap(Reorder::getProductId, r -> r, (a, x) -> a));

        PosRequisitionMst req = new PosRequisitionMst();
        req.setPidOrg(orgId);
        req.setPidBranch(branch);
        req.setRequisitionNo(nextRequisitionNo(orgId));
        req.setReqDate(LocalDate.now());
        // Raised as a draft, not pushed straight at an approver. The buyer has
        // to look at it: these are suggestions, and the whole design of this
        // page is that a person decides.
        req.setStage("DRAFT");
        req.setStatus("Y");
        req.setSource("AI");
        req.setPidCreatedBy(SecurityUtils.getCurrentUserId());
        req.setPidSupplier(supplierId);
        req.setRemarks(clip(remarks == null || remarks.isBlank()
                ? "Raised from the AI Copilot — " + boxesByProduct.size() + " lines"
                : remarks.trim(), 240));

        BigDecimal total = BigDecimal.ZERO;

        for (Map.Entry<Long, Integer> e : boxesByProduct.entrySet()) {
            Reorder r = byId.get(e.getKey());
            if (r == null) {
                throw new ValidationException(
                        "Product " + e.getKey() + " is no longer on the recommendation list. "
                        + "Refresh the page — its stock or sales have changed since it was shown.");
            }
            // Deliberately not checked against the supplier's manufacturer.
            //
            // A requisition records what is short, and a shortage does not
            // respect who makes things. Refusing a mixed one here forces the
            // buyer to keep four lists by hand — which is the work this was
            // meant to remove. The constraint belongs on the purchase order,
            // which is single-supplier by construction, and the split step is
            // where a requisition becomes one. Naming a supplier here scopes
            // the recommendations and records the intent; it does not restrict
            // what may be asked for.
            int boxes = e.getValue() == null ? r.getSuggestedBoxes() : Math.max(1, e.getValue());

            PosRequisitionDtl d = new PosRequisitionDtl();
            d.setRequisition(req);
            d.setPidProduct(r.getProductId());
            d.setProductName(r.getProductName());
            d.setPidCompany(r.getCompanyId());
            d.setCompanyName(r.getCompanyName());
            d.setRequestedQty(BigDecimal.valueOf(boxes));
            d.setAiRecommendedQty(BigDecimal.valueOf(r.getSuggestedBoxes()));
            d.setAvgMonthlySales(scale(r.getVelocityPerDay().multiply(BigDecimal.valueOf(30)), 2));
            d.setCurrentStock(r.getStockPcs());
            d.setEstimatedUnitCost(r.getCostPerBox());
            d.setPriority(r.getPriority());
            d.setUnit("BOX");
            d.setStatus("Y");
            d.setRemarks(clip(String.join(", ", r.getReasons()), 240));
            req.getDetails().add(d);

            total = total.add(r.getCostPerBox().multiply(BigDecimal.valueOf(boxes)));
        }

        PosRequisitionMst saved = reqRepo.save(req);
        log.info("AI requisition {} raised with {} lines, about {}",
                 saved.getRequisitionNo(), saved.getDetails().size(), total);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getPidRequisition());
        out.put("requisitionNo", saved.getRequisitionNo());
        out.put("stage", saved.getStage());
        out.put("lines", saved.getDetails().size());
        out.put("estimatedCost", scale(total, 2));
        return out;
    }

    /**
     * The same REQ-yyyyMMdd-0001 series the requisition module issues, using
     * the same counter — parsing every digit of the last number overflows an
     * int and quietly restarts at 1, which collides on the unique index.
     */
    private String nextRequisitionNo(Long orgId) {
        return "REQ-" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
               + "-" + String.format("%04d",
                       RequisitionServiceImpl.nextSequence(reqRepo.findLastRequisitionNo(orgId)));
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /** One product's numbers, as the database handed them over. */
    private static final class Signal {
        Long productId; String productName; String sku;
        Long companyId; String companyName; String categoryName;
        int pcsPerBox; BigDecimal costPerBox; BigDecimal mrpPerBox;
        BigDecimal stockPcs; BigDecimal sellablePcs; BigDecimal stockValue;
        LocalDate nearestExpiry; BigDecimal expiringPcs; BigDecimal expiringValue;
        BigDecimal perDay; BigDecimal trendPct; int sellingDays; LocalDate lastSold;
        BigDecimal reorderLevel;

        BigDecimal mrpPerPcs()  { return div(mrpPerBox, pcsPerBox); }
        BigDecimal costPerPcs() { return div(costPerBox, pcsPerBox); }
        private static BigDecimal div(BigDecimal v, int by) {
            return by <= 0 ? BigDecimal.ZERO
                           : v.divide(BigDecimal.valueOf(by), 4, RoundingMode.HALF_UP);
        }
    }

    private record Revenue(BigDecimal revenue, BigDecimal cost, BigDecimal pcs, long invoices) {
        static final Revenue EMPTY =
                new Revenue(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private List<Signal> signals(Long orgId, Long branchId, Long companyId) {
        return repo.productSignals(orgId, branchId, companyId, EXPIRY_HORIZON_DAYS).stream().map(r -> {
            Signal s = new Signal();
            s.productId    = lng(r[0]);
            s.productName  = str(r[1]);
            s.sku          = str(r[2]);
            s.companyId    = lng(r[3]);
            s.companyName  = str(r[4]);
            s.categoryName = str(r[5]);
            s.pcsPerBox    = Math.max(1, dec(r[6]).intValue());
            s.costPerBox   = dec(r[7]);
            s.mrpPerBox    = dec(r[8]);
            s.stockPcs     = dec(r[9]);
            s.sellablePcs  = dec(r[10]);
            s.stockValue   = dec(r[11]);
            s.nearestExpiry= date(r[12]);
            s.expiringPcs  = dec(r[13]);
            s.expiringValue= dec(r[14]);

            BigDecimal pcs30 = dec(r[15]).max(BigDecimal.ZERO);
            BigDecimal prev30 = dec(r[16]).max(BigDecimal.ZERO);
            s.perDay = pcs30.divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
            // A trend needs something to compare against. Against zero, any
            // sale at all is "infinite growth" — which tells the buyer nothing.
            s.trendPct = prev30.signum() > 0
                    ? scale(pcs30.subtract(prev30).multiply(BigDecimal.valueOf(100))
                            .divide(prev30, 4, RoundingMode.HALF_UP), 0)
                    : null;
            s.sellingDays  = dec(r[18]).intValue();
            s.lastSold     = date(r[19]);
            s.reorderLevel = dec(r[20]);
            return s;
        }).toList();
    }

    private Map<Long, Revenue> revenue(Long orgId, Long branchId, int days) {
        Map<Long, Revenue> out = new HashMap<>();
        for (Object[] r : repo.productRevenue(orgId, branchId, days)) {
            out.put(lng(r[0]), new Revenue(dec(r[1]), dec(r[3]), dec(r[2]),
                                           dec(r[4]).longValue()));
        }
        return out;
    }

    private boolean isDead(Signal s) {
        return s.stockPcs.signum() > 0
                && s.perDay.signum() <= 0
                && (s.lastSold == null
                    || ChronoUnit.DAYS.between(s.lastSold, LocalDate.now()) >= DEAD_STOCK_DAYS);
    }

    private Integer coverDays(Signal s) {
        if (s.perDay.signum() <= 0) return null;
        return s.sellablePcs.divide(s.perDay, 0, RoundingMode.FLOOR).intValue();
    }

    private static BigDecimal scale(BigDecimal v, int dp) {
        return (v == null ? BigDecimal.ZERO : v).setScale(dp, RoundingMode.HALF_UP);
    }
    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO
                         : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
    private static Long lng(Object o)   { return o == null ? null : ((Number) o).longValue(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static LocalDate date(Object o) {
        return o == null ? null
             : o instanceof java.sql.Date d ? d.toLocalDate()
             : o instanceof LocalDate ld ? ld
             : LocalDate.parse(o.toString().substring(0, 10));
    }
    private static String clip(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
