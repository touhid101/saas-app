package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.SalesPercentRequest;
import com.friendspharma.app.dto.response.SalesPercentResponse;
import com.friendspharma.app.dto.response.SalesPercentResponse.BulkResult;
import com.friendspharma.app.dto.response.SalesPercentResponse.BulkResult.RowResult;
import com.friendspharma.app.dto.response.SalesPercentResponse.UnitPrice;
import com.friendspharma.app.dto.response.StockResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.SalesPercentService;
import com.friendspharma.app.util.SalesPriceCalculator;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sales% maintenance.
 *
 * <p>Implements the SRS rule {@code Unit Sales Price = MRP − (MRP × Sales% / 100)}
 * across six slots: BOX / STRIP / PCS, for regular and special customers.
 *
 * <p>Two invariants:
 * <ol>
 *   <li><b>Prices are always derived, never stored or accepted.</b> Only the six
 *       percentages persist; every price in a response is computed by
 *       {@link SalesPriceCalculator} at read time, so a price can never drift
 *       out of step with the MRP it came from.</li>
 *   <li><b>Null and zero are still different in a slot, and the same in a row.</b>
 *       Per slot, null means the unit is not sellable in that unit at all, while
 *       zero means sellable at full MRP — so clearing needs an explicit flag,
 *       not an omitted field. But a row whose six slots are <em>all</em> zero has
 *       said nothing that a row of nulls did not: sell everything at MRP. Such a
 *       row reports {@code unconfigured}, and the "needs pricing" filter finds
 *       it. Without that, a grid which shows every empty box as 0 would call an
 *       untouched catalogue fully priced the moment somebody saved a row.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesPercentServiceImpl implements SalesPercentService {

    private static final int MAX_BULK = 2000;

    private final PosProductInfoRepository     productRepo;
    private final BranchScopeService branchScope;
    private final PosGenericInfoRepository     genericRepo;
    private final PosCompanyInfoRepository     companyRepo;
    private final PosProductCategoryRepository categoryRepo;
    private final PosProductUnitRepository     unitRepo;
    private final PosBoxInfoRepository         boxRepo;
    private final PosLeafInfoRepository        leafRepo;
    private final PosStockBatchRepository      batchRepo;

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SalesPercentResponse> search(SalesPercentFilter filter, Pageable pageable) {
        Long orgId = requireOrgId();

        if (Boolean.TRUE.equals(filter.belowCost())) {
            return searchBelowCost(orgId, filter, pageable);
        }

        Page<PosProductInfo> page = productRepo.findAll(buildSpec(orgId, filter), pageable);

        Labels labels = Labels.forPage(page.getContent(), genericRepo, companyRepo, categoryRepo, unitRepo);
        Map<Long, int[]> pieces = piecesForPage(page.getContent());
        Map<Long, BigDecimal> costs = costPerPcs(page.getContent().stream()
                .map(PosProductInfo::getPidProduct).collect(Collectors.toSet()));

        List<SalesPercentResponse> rows = page.getContent().stream()
                .map(p -> toResponse(p, labels, pieces, costs))
                .toList();

        return PageResponse.from(page, rows);
    }

    /**
     * The lines being sold at or below what they cost.
     *
     * <p><b>Why this cannot be paged by the database.</b> The cost is a weighted
     * average across the batches on hand, and the answer depends on the MRP
     * apportioned to each unit as well as the percentage. None of that is a
     * column, so there is no predicate to write: the comparison exists only once
     * a row has been priced.
     *
     * <p>So the filter is applied over the whole matching set and the page is
     * taken afterwards. That costs one unpaged read of the products already
     * narrowed by every other filter — noticeably more than a normal page, and
     * the reason it happens only when this filter is switched on.
     *
     * <p><b>A product with no stock is not included.</b> Its cost is unknown, and
     * an unknown cost is not evidence of a loss.
     */
    private PageResponse<SalesPercentResponse> searchBelowCost(Long orgId, SalesPercentFilter filter,
                                                               Pageable pageable) {
        List<PosProductInfo> all = productRepo.findAll(buildSpec(orgId, filter), pageable.getSort());
        Map<Long, BigDecimal> costs = costPerPcs(all.stream()
                .map(PosProductInfo::getPidProduct).collect(Collectors.toSet()));
        Map<Long, int[]> pieces = piecesForPage(all);

        List<PosProductInfo> hits = all.stream()
                .filter(p -> isBelowCost(p, pieces, costs))
                .toList();

        int from = (int) Math.min(pageable.getOffset(), hits.size());
        int to   = Math.min(from + pageable.getPageSize(), hits.size());
        List<PosProductInfo> slice = hits.subList(from, to);

        Labels labels = Labels.forPage(slice, genericRepo, companyRepo, categoryRepo, unitRepo);
        List<SalesPercentResponse> rows = slice.stream()
                .map(p -> toResponse(p, labels, pieces, costs))
                .toList();

        return PageResponse.from(new PageImpl<>(rows, pageable, hits.size()), rows);
    }

    /**
     * One product's six prices and the three unit costs they are judged against.
     *
     * <p>Extracted so the filter and the row's own ▼ marker are the same
     * arithmetic rather than two copies of it. They must agree: a filter that
     * selects rows the grid does not then mark is worse than no filter, because
     * it looks like the marker is broken.
     */
    /* Package-private, not private: the below-cost rule is the one piece of
       arithmetic on this screen that decides money, and it is worth a test. */
    record Priced(SalesPriceCalculator.UnitPrices prices,
                          BigDecimal costBox, BigDecimal costStrip, BigDecimal costPcs) {

        /** Whether any of the six units prices at or under its own share of cost. */
        boolean anyBelowCost() {
            return under(prices.boxPrice(), costBox)
                || under(prices.stripPrice(), costStrip)
                || under(prices.pcsPrice(), costPcs)
                || under(prices.specialBoxPrice(), costBox)
                || under(prices.specialStripPrice(), costStrip)
                || under(prices.specialPcsPrice(), costPcs);
        }

        /** At or below, not merely below: breaking even is not a margin. */
        static boolean under(BigDecimal price, BigDecimal cost) {
            return price != null && cost != null && price.compareTo(cost) <= 0;
        }
    }

    /**
     * Prices and costs for one product.
     *
     * <p>The MRP is apportioned to each unit before the percentage is applied —
     * pricing a single tablet off a box's MRP put one tablet of a ৳400 box at
     * ৳364.
     */
    private Priced priceOf(PosProductInfo p, Map<Long, int[]> pieces, Map<Long, BigDecimal> costs) {
        int[] units = pieces.getOrDefault(p.getPidProduct(), new int[]{0, 0});
        Integer perBox   = units[0] > 0 ? units[0] : null;
        Integer perStrip = units[1] > 0 ? units[1] : null;

        SalesPriceCalculator.UnitPrices prices = SalesPriceCalculator.unitPrices(
                p.getMrpPrice(), perBox, perStrip,
                p.getSalesPer(), p.getSalesPerLeaf(), p.getSalesPerPcs(),
                p.getSalesPerSpc(), p.getSalesPerSpcLeaf(), p.getSalesPerSpcRet());

        BigDecimal cPcs = costs.get(p.getPidProduct());
        return new Priced(prices, scale(cPcs, perBox), scale(cPcs, perStrip), cPcs);
    }

    /**
     * Whether this product sells any unit at or under cost.
     *
     * <p>A product with no stock is not included: its cost is unknown, and an
     * unknown cost is not evidence of a loss.
     */
    private boolean isBelowCost(PosProductInfo p, Map<Long, int[]> pieces, Map<Long, BigDecimal> costs) {
        return costs.get(p.getPidProduct()) != null && priceOf(p, pieces, costs).anyBelowCost();
    }

    @Override
    @Transactional(readOnly = true)
    public SalesPercentResponse getByProductId(Long productId) {
        PosProductInfo product = loadOwnedProduct(productId);
        Labels labels = Labels.forPage(List.of(product), genericRepo, companyRepo, categoryRepo, unitRepo);
        return toResponse(product, labels, piecesForPage(List.of(product)),
                          costPerPcs(Set.of(product.getPidProduct())));
    }

    /* ══════════════════════════════════════════════════════════════════════
       WRITE
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public SalesPercentResponse update(Long productId, SalesPercentRequest request) {
        PosProductInfo product = loadOwnedProduct(productId);
        applyPercentages(product, request, true);
        PosProductInfo saved = productRepo.save(product);

        log.info("Sales% updated for {} ({}): box={} strip={} pcs={} | spc box={} strip={} pcs={}",
                saved.getProductCode(), saved.getProductName(),
                saved.getSalesPer(), saved.getSalesPerLeaf(), saved.getSalesPerPcs(),
                saved.getSalesPerSpc(), saved.getSalesPerSpcLeaf(), saved.getSalesPerSpcRet());

        Labels labels = Labels.forPage(List.of(saved), genericRepo, companyRepo, categoryRepo, unitRepo);
        return toResponse(saved, labels, piecesForPage(List.of(saved)),
                          costPerPcs(Set.of(saved.getPidProduct())));
    }

    @Override
    @Transactional
    public BulkResult applyBulk(SalesPercentRequest.Bulk request) {
        Long orgId = requireOrgId();

        List<Long> ids = resolveTargets(orgId, request);

        // One query for the whole selection, then filter to this tenant — a
        // foreign id is reported per-row rather than failing the batch.
        Map<Long, PosProductInfo> found = productRepo.findAllById(ids).stream()
                .filter(p -> orgId.equals(p.getPidOrg()))
                .collect(Collectors.toMap(PosProductInfo::getPidProduct, Function.identity(), (a, b) -> a));

        List<RowResult> results = new ArrayList<>(ids.size());
        List<PosProductInfo> toSave = new ArrayList<>();
        int updated = 0, skipped = 0, failed = 0;

        for (Long id : ids) {
            PosProductInfo product = found.get(id);
            if (product == null) {
                failed++;
                results.add(RowResult.builder().productId(id).status("FAILED")
                        .reason("Product not found in your organization.").build());
                continue;
            }

            boolean changed = applyPercentages(product, request.getPercentages(), request.isOverwriteExisting());
            if (changed) {
                updated++;
                toSave.add(product);
                results.add(RowResult.builder().productId(id).productName(product.getProductName())
                        .status("UPDATED").build());
            } else {
                skipped++;
                results.add(RowResult.builder().productId(id).productName(product.getProductName())
                        .status("SKIPPED")
                        .reason("Already priced — enable \"overwrite existing\" to replace it.").build());
            }
        }

        if (!toSave.isEmpty()) productRepo.saveAll(toSave);

        log.info("Bulk Sales% for org {}: {} updated, {} skipped, {} failed of {}",
                orgId, updated, skipped, failed, ids.size());

        return BulkResult.builder()
                .totalRequested(ids.size()).updated(updated).skipped(skipped).failed(failed)
                .results(results).build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       LOOKUPS
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<?>> getFilterLookups() {
        Long orgId = requireOrgId();
        Map<String, List<?>> lookups = new LinkedHashMap<>();

        lookups.put("companies", companyRepo.findByPidOrgAndPidBranchAndStatusOrderByCompanyName(orgId, branch(), "Y").stream()
                .map(c -> option(c.getPidCompany(), c.getCompanyName())).toList());
        lookups.put("categories", categoryRepo.findByPidOrgAndPidBranchAndStatusOrderByCategoryName(orgId, branch(), "Y").stream()
                .map(c -> option(c.getPidCategory(), c.getCategoryName())).toList());
        lookups.put("generics", genericRepo.findByPidOrgAndPidBranchAndStatusOrderByGenericName(orgId, branch(), "Y").stream()
                .map(g -> option(g.getPidGeneric(), g.getGenericName())).toList());
        lookups.put("units", List.of(
                Map.of("id", "BOX",   "name", "Box"),
                Map.of("id", "STRIP", "name", "Strip"),
                Map.of("id", "PCS",   "name", "Pieces")));
        lookups.put("tiers", List.of(
                Map.of("id", "REGULAR", "name", "Wholesale / Retail"),
                Map.of("id", "SPECIAL", "name", "Special Customer")));
        return lookups;
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Works out which products a bulk apply targets.
     *
     * <p>Either an explicit id list or a scope — a scope resolves through the
     * same specification the grid uses, so "every product of this company" is
     * described exactly as the grid's own filter describes it and the two can
     * never disagree about what matches.
     */
    private List<Long> resolveTargets(Long orgId, SalesPercentRequest.Bulk request) {
        boolean hasIds   = request.getProductIds() != null && !request.getProductIds().isEmpty();
        boolean hasScope = request.getScope() != null;

        if (hasIds && hasScope) {
            throw new BusinessException(
                    "Choose either specific products or a company/category scope, not both.",
                    HttpStatus.BAD_REQUEST, "AMBIGUOUS_BULK_TARGET");
        }
        if (!hasIds && !hasScope) {
            throw new BusinessException("Select products, or choose a company to apply to.",
                    HttpStatus.BAD_REQUEST, "EMPTY_SELECTION");
        }

        if (hasIds) {
            if (request.getProductIds().size() > MAX_BULK) {
                throw new BusinessException("A single bulk update is limited to " + MAX_BULK + " products.",
                        HttpStatus.BAD_REQUEST, "BULK_TOO_LARGE");
            }
            return request.getProductIds();
        }

        SalesPercentRequest.Scope scope = request.getScope();
        if (scope.isEmpty()) {
            // Guards against a blank scope quietly repricing the whole catalogue.
            throw new BusinessException(
                    "Choose at least a company, category or generic to apply to.",
                    HttpStatus.BAD_REQUEST, "SCOPE_TOO_BROAD");
        }

        SalesPercentFilter filter = new SalesPercentFilter(
                null, scope.getCompanyId(), scope.getCategoryId(), scope.getGenericId(),
                scope.getStatus() != null ? scope.getStatus() : "ACTIVE",
                /* Bulk apply scopes never narrow to loss-making lines: the point is
                   to set a rate across a range, not to hunt for exceptions. */
                scope.getConfigured(), null);

        List<Long> ids = productRepo.findAll(buildSpec(orgId, filter)).stream()
                .map(PosProductInfo::getPidProduct)
                .toList();

        if (ids.isEmpty()) {
            throw new BusinessException("No products match that selection.",
                    HttpStatus.BAD_REQUEST, "SCOPE_MATCHED_NOTHING");
        }
        if (ids.size() > MAX_BULK) {
            throw new BusinessException(
                    "That selection matches " + ids.size() + " products, over the "
                    + MAX_BULK + " limit for one update. Narrow it by category or generic.",
                    HttpStatus.BAD_REQUEST, "BULK_TOO_LARGE");
        }

        log.info("Scoped Sales% apply for org {}: company={} category={} generic={} -> {} products",
                orgId, scope.getCompanyId(), scope.getCategoryId(), scope.getGenericId(), ids.size());
        return ids;
    }

    /**
     * How many products a scope would touch, without touching them.
     * Lets the UI say "this will update 187 products" before anything happens.
     */
    @Override
    @Transactional(readOnly = true)
    public long countInScope(SalesPercentRequest.Scope scope) {
        Long orgId = requireOrgId();
        if (scope == null || scope.isEmpty()) return 0L;

        SalesPercentFilter filter = new SalesPercentFilter(
                null, scope.getCompanyId(), scope.getCategoryId(), scope.getGenericId(),
                scope.getStatus() != null ? scope.getStatus() : "ACTIVE",
                /* Bulk apply scopes never narrow to loss-making lines: the point is
                   to set a rate across a range, not to hunt for exceptions. */
                scope.getConfigured(), null);
        return productRepo.count(buildSpec(orgId, filter));
    }

    /**
     * Copies percentages onto the product.
     *
     * @param overwrite when false, a unit that already has a percentage keeps
     *                  it and only unpriced units are filled in — what makes a
     *                  bulk apply safe to run over a partly-priced catalogue
     * @return true when anything actually changed
     */
    private boolean applyPercentages(PosProductInfo p, SalesPercentRequest r, boolean overwrite) {
        boolean changed = false;
        changed |= set(p::getSalesPer,        p::setSalesPer,        r.getBoxPct(),          r.isClearBox(),          overwrite);
        changed |= set(p::getSalesPerLeaf,    p::setSalesPerLeaf,    r.getStripPct(),        r.isClearStrip(),        overwrite);
        changed |= set(p::getSalesPerPcs,     p::setSalesPerPcs,     r.getPcsPct(),          r.isClearPcs(),          overwrite);
        changed |= set(p::getSalesPerSpc,     p::setSalesPerSpc,     r.getSpecialBoxPct(),   r.isClearSpecialBox(),   overwrite);
        changed |= set(p::getSalesPerSpcLeaf, p::setSalesPerSpcLeaf, r.getSpecialStripPct(), r.isClearSpecialStrip(), overwrite);
        changed |= set(p::getSalesPerSpcRet,  p::setSalesPerSpcRet,  r.getSpecialPcsPct(),   r.isClearSpecialPcs(),   overwrite);
        return changed;
    }

    /** One slot's write rule. Returns true when the stored value changed. */
    private boolean set(java.util.function.Supplier<BigDecimal> getter,
                        java.util.function.Consumer<BigDecimal> setter,
                        BigDecimal incoming, boolean clear, boolean overwrite) {
        BigDecimal current = getter.get();

        if (clear) {
            if (current == null) return false;
            setter.accept(null);
            return true;
        }
        if (incoming == null) return false;                       // omitted: leave alone
        if (!overwrite && current != null) return false;          // already priced: keep
        if (current != null && current.compareTo(incoming) == 0) return false;

        setter.accept(incoming);
        return true;
    }

    private Specification<PosProductInfo> buildSpec(Long orgId, SalesPercentFilter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            // Same rule as the product grid: a product carrying a branch belongs to
            // that pharmacy, one carrying none is the shared catalogue.
            p.add(cb.equal(root.get("pidOrg"), orgId));
            p.add(cb.or(cb.isNull(root.get("pidBranch")),
                        cb.equal(root.get("pidBranch"), branch())));

            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase() + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("productName")), like),
                        cb.like(cb.lower(root.get("productCode")), like),
                        cb.like(cb.lower(root.get("sku")),         like),
                        cb.like(cb.lower(root.get("barcode")),     like)));
            }
            if (f.companyId()  != null) p.add(cb.equal(root.get("pidCompany"),  f.companyId()));
            if (f.categoryId() != null) p.add(cb.equal(root.get("pidCategory"), f.categoryId()));
            if (f.genericId()  != null) p.add(cb.equal(root.get("pidGeneric"),  f.genericId()));

            if (f.status() != null && !f.status().isBlank() && !"ALL".equalsIgnoreCase(f.status())) {
                p.add(cb.equal(root.get("status"), "ACTIVE".equalsIgnoreCase(f.status()) ? "Y" : "N"));
            }

            // "Configured" means at least one slot carries a real discount. Zero
            // counts as unpriced alongside null: a zero percentage sells at MRP,
            // which is what a row nobody has priced does, and the grid now shows
            // every empty box as 0 — so treating 0 as "priced" would report a
            // catalogue nobody had touched as fully priced.
            if (f.configured() != null) {
                Predicate nothingSet = cb.and(
                        unpriced(cb, root, "salesPer"),        unpriced(cb, root, "salesPerLeaf"),
                        unpriced(cb, root, "salesPerPcs"),     unpriced(cb, root, "salesPerSpc"),
                        unpriced(cb, root, "salesPerSpcLeaf"), unpriced(cb, root, "salesPerSpcRet"));
                p.add(f.configured() ? cb.not(nothingSet) : nothingSet);
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private SalesPercentResponse toResponse(PosProductInfo p, Labels labels, Map<Long, int[]> pieces,
                                            Map<Long, BigDecimal> costPerPcs) {
        BigDecimal mrp = p.getMrpPrice();
        int[] units = pieces.getOrDefault(p.getPidProduct(), new int[]{0, 0});
        Integer perBox   = units[0] > 0 ? units[0] : null;
        Integer perStrip = units[1] > 0 ? units[1] : null;

        // The same arithmetic the "at or below cost" filter selects on — shared
        // rather than repeated, so the filter and the ▼ below cannot disagree.
        Priced priced = priceOf(p, pieces, costPerPcs);
        SalesPriceCalculator.UnitPrices prices = priced.prices();

        BigDecimal cPcs   = priced.costPcs();
        BigDecimal cStrip = priced.costStrip();
        BigDecimal cBox   = priced.costBox();

        SalesPercentResponse.UnitPrice box         = unit(prices.boxMrp(),   prices.boxPrice(),          p.getSalesPer(),        cBox);
        SalesPercentResponse.UnitPrice strip       = unit(prices.stripMrp(), prices.stripPrice(),        p.getSalesPerLeaf(),    cStrip);
        SalesPercentResponse.UnitPrice pcs         = unit(prices.pcsMrp(),   prices.pcsPrice(),          p.getSalesPerPcs(),     cPcs);
        SalesPercentResponse.UnitPrice spcBox      = unit(prices.boxMrp(),   prices.specialBoxPrice(),   p.getSalesPerSpc(),     cBox);
        SalesPercentResponse.UnitPrice spcStrip    = unit(prices.stripMrp(), prices.specialStripPrice(), p.getSalesPerSpcLeaf(), cStrip);
        SalesPercentResponse.UnitPrice spcPcs      = unit(prices.pcsMrp(),   prices.specialPcsPrice(),   p.getSalesPerSpcRet(),  cPcs);

        boolean anyBelowCost = priced.anyBelowCost();

        return SalesPercentResponse.builder()
                .cost(cPcs == null ? null : StockResponses.CostBlock.builder()
                        .costPerPcs(cPcs.setScale(4, RoundingMode.HALF_UP))
                        .costPerStrip(cStrip)
                        .costPerBox(cBox)
                        .pcsPerBox(perBox)
                        .pcsPerStrip(perStrip)
                        .averaged(true)
                        .build())
                .belowCost(anyBelowCost)
                .productId(p.getPidProduct())
                .productCode(p.getProductCode())
                .productName(p.getProductName())
                .sku(p.getSku())
                .genericName(labels.generic(p.getPidGeneric()))
                .companyName(labels.company(p.getPidCompany()))
                .categoryName(labels.category(p.getPidCategory()))
                .unitName(labels.unitOr(p.getPidUnitId(), p.getPidUnit()))
                .mrp(mrp)
                .pcsPerBox(perBox)
                .pcsPerStrip(perStrip)
                .box(box)
                .strip(strip)
                .pcs(pcs)
                .specialBox(spcBox)
                .specialStrip(spcStrip)
                .specialPcs(spcPcs)
                .regularComplete(box.getPrice() != null && strip.getPrice() != null && pcs.getPrice() != null)
                // Zero everywhere is the same statement as nothing anywhere: sell
                // at MRP. Reported as unpriced so the count, the chips and the
                // "not priced" label in the grid all say the same thing.
                .unconfigured(!hasAnyDiscount(p))
                .status("Y".equals(p.getStatus()) ? "ACTIVE" : "INACTIVE")
                .build();
    }

    /**
     * One unit's pricing, against its own share of MRP and its own cost.
     *
     * <p>{@code belowCost} is a warning, never a block: a pharmacy does
     * discount below cost deliberately — to clear stock that is about to
     * expire, for instance. The row is flagged so the decision is visible, not
     * prevented.
     */
    private UnitPrice unit(BigDecimal unitMrp, BigDecimal price, BigDecimal pct, BigDecimal cost) {
        BigDecimal discount = (unitMrp != null && price != null)
                ? unitMrp.subtract(price) : null;

        return UnitPrice.builder()
                .pct(pct)
                .price(price)
                .mrp(unitMrp)
                .discountAmount(discount)
                .cost(cost)
                .belowCost(Priced.under(price, cost))
                .configured(pct != null)
                .build();
    }

    /** True when at least one of the six slots takes something off MRP. */
    private static boolean hasAnyDiscount(PosProductInfo p) {
        return Stream.of(p.getSalesPer(), p.getSalesPerLeaf(), p.getSalesPerPcs(),
                         p.getSalesPerSpc(), p.getSalesPerSpcLeaf(), p.getSalesPerSpcRet())
                .anyMatch(v -> v != null && v.compareTo(BigDecimal.ZERO) != 0);
    }

    /** Null or zero — the two ways a slot can carry no pricing decision. */
    private static Predicate unpriced(jakarta.persistence.criteria.CriteriaBuilder cb,
                                      jakarta.persistence.criteria.Root<PosProductInfo> root,
                                      String field) {
        return cb.or(cb.isNull(root.get(field)),
                     cb.equal(root.get(field), BigDecimal.ZERO));
    }

    /** Piece cost × pieces in the unit, or null when the packing is unknown. */
    private static BigDecimal scale(BigDecimal costPerPcs, Integer pcsPerUnit) {
        if (costPerPcs == null || pcsPerUnit == null || pcsPerUnit <= 0) return null;
        return costPerPcs.multiply(BigDecimal.valueOf(pcsPerUnit)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Landed cost per piece for a page of products, in one query.
     *
     * <p>Weighted across the lots on hand in the caller's branch — total value
     * over total pieces. A product holding nothing is absent, and its row shows
     * no cost rather than a zero.
     */
    private Map<Long, BigDecimal> costPerPcs(Collection<Long> productIds) {
        Long branchId = SecurityUtils.getCurrentBranchId();
        if (branchId == null || productIds.isEmpty()) return Map.of();

        Map<Long, BigDecimal> out = new HashMap<>();
        for (Object[] r : batchRepo.stockRollupByProduct(branchId)) {
            Long pid = ((Number) r[0]).longValue();
            if (!productIds.contains(pid)) continue;
            BigDecimal pcs   = (BigDecimal) r[1];
            BigDecimal value = (BigDecimal) r[2];
            if (pcs != null && pcs.compareTo(BigDecimal.ZERO) > 0 && value != null) {
                out.put(pid, value.divide(pcs, 6, RoundingMode.HALF_UP));
            }
        }
        return out;
    }

    /**
     * Pieces per box and per strip for a page of products, resolved in two
     * queries rather than two per row.
     *
     * @return productId → {pcsPerBox, pcsPerStrip}
     */
    private Map<Long, int[]> piecesForPage(List<PosProductInfo> products) {
        Set<Long> boxIds = products.stream().map(PosProductInfo::getPidBox)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (boxIds.isEmpty()) return Map.of();

        Map<Long, PosBoxInfo> boxes = boxRepo.findAllById(boxIds).stream()
                .collect(Collectors.toMap(PosBoxInfo::getPidBox, Function.identity(), (a, b) -> a));

        Set<Long> leafIds = boxes.values().stream().map(PosBoxInfo::getPidLeaf)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PosLeafInfo> leaves = leafIds.isEmpty() ? Map.of()
                : leafRepo.findAllById(leafIds).stream()
                    .collect(Collectors.toMap(PosLeafInfo::getPidLeaf, Function.identity(), (a, b) -> a));

        Map<Long, int[]> out = new HashMap<>();
        for (PosProductInfo p : products) {
            if (p.getPidBox() == null) continue;
            PosBoxInfo box = boxes.get(p.getPidBox());
            if (box == null) continue;
            PosLeafInfo leaf = box.getPidLeaf() == null ? null : leaves.get(box.getPidLeaf());
            int pcsPerStrip = leaf != null && leaf.getNoOfPcs() != null ? leaf.getNoOfPcs() : 0;
            int strips      = box.getNoOfLeaf() != null ? box.getNoOfLeaf() : 0;
            out.put(p.getPidProduct(), new int[]{ strips * pcsPerStrip, pcsPerStrip });
        }
        return out;
    }

    private PosProductInfo loadOwnedProduct(Long productId) {
        Long orgId = requireOrgId();
        return productRepo.findByIdAndOrg(productId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private Long requireOrgId() {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) {
            throw new BusinessException("No organization is associated with your session. Please log in again.",
                    HttpStatus.UNAUTHORIZED, "NO_ORG_CONTEXT");
        }
        return orgId;
    }

    private static Map<String, Object> option(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    /** Per-page master-data label cache, so the grid stays at a constant query count. */
    private record Labels(Map<Long, String> generics, Map<Long, String> companies,
                          Map<Long, String> categories, Map<Long, String> units) {

        static Labels forPage(List<PosProductInfo> page,
                              PosGenericInfoRepository genericRepo,
                              PosCompanyInfoRepository companyRepo,
                              PosProductCategoryRepository categoryRepo,
                              PosProductUnitRepository unitRepo) {
            Set<Long> g = ids(page, PosProductInfo::getPidGeneric);
            Set<Long> c = ids(page, PosProductInfo::getPidCompany);
            Set<Long> k = ids(page, PosProductInfo::getPidCategory);
            Set<Long> u = ids(page, PosProductInfo::getPidUnitId);

            return new Labels(
                    g.isEmpty() ? Map.of() : genericRepo.findAllById(g).stream()
                            .collect(Collectors.toMap(PosGenericInfo::getPidGeneric, PosGenericInfo::getGenericName, (a, b) -> a)),
                    c.isEmpty() ? Map.of() : companyRepo.findAllById(c).stream()
                            .collect(Collectors.toMap(PosCompanyInfo::getPidCompany, PosCompanyInfo::getCompanyName, (a, b) -> a)),
                    k.isEmpty() ? Map.of() : categoryRepo.findAllById(k).stream()
                            .collect(Collectors.toMap(PosProductCategory::getPidCategory, PosProductCategory::getCategoryName, (a, b) -> a)),
                    u.isEmpty() ? Map.of() : unitRepo.findAllById(u).stream()
                            .collect(Collectors.toMap(PosProductUnit::getPidUnit, PosProductUnit::getUnitName, (a, b) -> a)));
        }

        private static Set<Long> ids(List<PosProductInfo> page, Function<PosProductInfo, Long> f) {
            return page.stream().map(f).filter(Objects::nonNull).collect(Collectors.toSet());
        }

        // Map.of() rejects a null key, and optional FKs here are routinely null.
        String generic(Long id)  { return id == null ? null : generics.get(id); }
        String company(Long id)  { return id == null ? null : companies.get(id); }
        String category(Long id) { return id == null ? null : categories.get(id); }
        String unitOr(Long id, String fallback) {
            String found = id == null ? null : units.get(id);
            return found != null ? found : fallback;
        }
    }

    /**
     * The pharmacy whose master data this request may see.
     *
     * <p>Deliberately not {@code SecurityUtils.getCurrentBranchId()}: that
     * returns null where an account has no branch, and a null branch widens the
     * lookups back to every pharmacy in the organisation.
     */
    private Long branch() { return branchScope.resolve(null); }
}
