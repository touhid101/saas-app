package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.response.StockResponses;
import com.friendspharma.app.dto.response.StockResponses.*;
import com.friendspharma.app.entity.PosCompanyInfo;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.entity.PosStockBatch;
import com.friendspharma.app.entity.PosStockMovement;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.PosBoxInfoRepository;
import com.friendspharma.app.repository.PosCompanyInfoRepository;
import com.friendspharma.app.repository.PosLeafInfoRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.repository.PosStockBatchRepository;
import com.friendspharma.app.repository.PosStockMovementRepository;
import com.friendspharma.app.service.interfaces.InventoryService;
import com.friendspharma.app.service.interfaces.StockService;
import com.friendspharma.app.util.SalesPriceCalculator;
import com.friendspharma.app.util.StockUnitConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Inventory reporting over branch-wise batch stock.
 *
 * <p>Every method here reads {@code POS_STOCK_BATCH}, filtered by branch in
 * SQL. The version this replaces did none of that: it summed raw
 * {@code POS_TRANSACTION_DTL} quantities in Java, unsigned (so a sale increased
 * stock), unconverted (so a box counted as one piece), without a status filter
 * (so unapproved drafts counted), and while accepting a {@code branchId} it
 * never used. Four separate {@code findAll()} calls loaded the whole detail
 * table into memory, twice within one method.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private static final int NEAR_EXPIRY_DAYS = 30;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PosStockBatchRepository    batchRepo;
    private final PosStockMovementRepository movementRepo;
    private final PosProductInfoRepository   productRepo;
    private final PosCompanyInfoRepository   companyRepo;
    private final PosBoxInfoRepository       boxRepo;
    private final PosLeafInfoRepository      leafRepo;
    private final StockService               stockService;
    /* Turns the stored filename into a URL the browser can fetch — the same
       helper Product Master uses, so both screens show the same image. */
    private final ProductImageStorageService  imageStorage;

    /* ══════════════════════════════════════════════════════════════════════
       FILTERS

       Company, SKU, name and category are product attributes; batch belongs to
       the stock. So the product-side filters are resolved once into an id set
       and every report then narrows on that set, rather than each query
       re-joining to the product table in its own slightly different way.
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The products a filter selects, or {@code null} when it selects everything.
     *
     * <p>An <em>empty</em> set is meaningfully different from null: it means the
     * filter matched nothing, and the report must come back empty rather than
     * unfiltered. Callers check {@link Scope#matchesNothing()} before querying.
     */
    private record Scope(Set<Long> productIds) {
        boolean filtering()      { return productIds != null; }
        boolean matchesNothing() { return productIds != null && productIds.isEmpty(); }
        Collection<Long> ids()   { return productIds == null ? List.of(0L) : productIds; }
    }

    /**
     * Whether a report can skip its query entirely.
     *
     * <p>Only when the filter matched no products <em>and</em> there is no free
     * text that could still match a batch. Bailing out on the product miss alone
     * is what made searching for a batch reference return nothing: no product is
     * called "B-002", so the scope came back empty and the query that would have
     * matched the batch never ran.
     */
    private static boolean nothingToShow(Scope scope, StockFilter filter) {
        return scope.matchesNothing() && searchBatchLike(filter) == null;
    }

    /** Products holding a lot whose reference matches {@code pattern}. Empty when null. */
    private Set<Long> productsHoldingBatch(Long branchId, String pattern) {
        if (pattern == null) return Set.of();
        return batchRepo
                .findBatchStock(branchId, false, List.of(0L), null, pattern, true, Pageable.unpaged())
                .getContent().stream()
                .map(PosStockBatch::getPidProduct)
                .collect(Collectors.toSet());
    }

    private Scope resolveScope(Long branchId, StockFilter filter) {
        StockFilter f = filter != null ? filter : StockFilter.none();
        if (f.noProductNarrowing()) return new Scope(null);

        Long orgId = orgOf(branchId);
        String like = likeOf(f.search());

        List<PosProductInfo> matches = productRepo.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
            // A product carrying a branch belongs to that pharmacy; one carrying
            // none is the shared catalogue. Without this the stock screen listed
            // every pharmacy's products, showing zero stock against each.
            if (orgId != null) p.add(cb.equal(root.get("pidOrg"), orgId));
            p.add(cb.or(cb.isNull(root.get("pidBranch")),
                        cb.equal(root.get("pidBranch"), branchId)));
            if (f.companyId() != null)  p.add(cb.equal(root.get("pidCompany"), f.companyId()));
            if (f.categoryId() != null) p.add(cb.equal(root.get("pidCategory"), f.categoryId()));
            if (f.productId() != null)  p.add(cb.equal(root.get("pidProduct"), f.productId()));
            if (like != null) {
                p.add(cb.or(
                        cb.like(cb.lower(root.get("productName")), like),
                        cb.like(cb.lower(root.get("sku")), like)));
            }
            return cb.and(p.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        return new Scope(matches.stream().map(PosProductInfo::getPidProduct)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private static Long   companyOf(StockFilter f) { return f == null ? null : f.companyId(); }
    private static String searchOf(StockFilter f)  { return f == null ? null : f.search(); }

    /** {@code %term%}, lower-cased — or null when there is no term. */
    private static String likeOf(String term) {
        if (term == null || term.isBlank()) return null;
        return "%" + term.trim().toLowerCase() + "%";
    }

    /** The explicit batch filter, as a LIKE pattern. Narrows on its own (AND). */
    private static String batchLike(StockFilter filter) {
        if (filter == null || !filter.hasBatch()) return null;
        return likeOf(filter.batchNo());
    }

    /**
     * The free-text term, matched against batch numbers as well as product name
     * and SKU.
     *
     * <p>Widens rather than narrows: a row qualifies if its <em>product</em>
     * matched the term OR its <em>batch</em> does. Someone holding a strip with
     * a number printed on it can type that number without first working out
     * whether it is a SKU or a batch.
     *
     * <p>Null when there is no search term, which is what makes the OR branch
     * inert rather than matching everything.
     */
    private static String searchBatchLike(StockFilter filter) {
        if (filter == null) return null;
        return likeOf(filter.search());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<?>> getFilterLookups(Long branchId) {
        List<Map<String, Object>> companies = batchRepo.findCompaniesWithStock(branchId).stream()
                .map(r -> option(asLong(r[0]), (String) r[1]))
                .toList();

        List<Map<String, Object>> batches = batchRepo.findBatchNumbersWithStock(branchId).stream()
                .map(b -> option(null, b))
                .toList();

        Map<String, List<?>> out = new LinkedHashMap<>();
        out.put("companies", companies);
        out.put("batches", batches);
        return out;
    }

    /**
     * Company name for a product, from a cache built once per request.
     *
     * <p>A page of 25 products is 25 lookups otherwise, and they repeat heavily
     * — a pharmacy catalogue has far more products than manufacturers.
     */
    private String companyName(Long companyId) {
        if (companyId == null) return null;
        return companyNameCache.computeIfAbsent(companyId,
                id -> companyRepo.findById(id).map(PosCompanyInfo::getCompanyName).orElse(null));
    }

    /**
     * Request-scoped only in effect: the bean is a singleton, but company names
     * are master data that changes rarely, and a stale name on a report is a
     * cosmetic issue rather than a wrong number.
     */
    private final Map<Long, String> companyNameCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static Map<String, Object> option(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    /* ══════════════════════════════════════════════════════════════════════
       DASHBOARD
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public StockDashboard getDashboard(Long branchId) {
        LocalDate today = LocalDate.now();

        // One query for value, pieces, product count, near-expiry and expired.
        // These were five passes over the transaction table, two of them
        // identical and back to back.
        Object[] row = unwrap(batchRepo.dashboardTotals(branchId, today, today.plusDays(NEAR_EXPIRY_DAYS)));

        return StockDashboard.builder()
                .branchId(branchId)
                .totalStockValue(decimal(row, 0))
                .totalPcs(decimal(row, 1))
                .productsInStock(number(row, 2))
                .nearExpiryCount(number(row, 3))
                .expiredCount(number(row, 4))
                // Unfiltered by design: the dashboard counts the whole branch,
                // so the number a user clicks through on is the number the
                // alerts tab then shows before they narrow it themselves.
                .lowStockCount(batchRepo.findLowStock(orgOf(branchId), branchId, today, null, null).size())
                .outOfStockCount(batchRepo.findOutOfStock(orgOf(branchId), branchId, today, null, null).size())
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       STOCK LEDGER — per product
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductStockRow> getStockLedger(Long branchId, StockFilter filter,
                                                        Pageable pageable) {
        StockFilter f = filter != null ? filter : StockFilter.none();

        // Rollup comes back already aggregated, one row per product, in SQL.
        Map<Long, Rollup> rollups = batchRepo.stockRollupByProduct(branchId).stream()
                .map(Rollup::of)
                .collect(Collectors.toMap(Rollup::productId, Function.identity(), (a, b) -> a));

        // The batch filter narrows which products appear at all — only those
        // holding a lot of that reference. Without this, filtering by batch on
        // the product-level ledger would silently do nothing.
        Set<Long> batchScope = f.hasBatch() ? productsHoldingBatch(branchId, batchLike(f)) : null;

        // A free-text term may be a batch reference rather than a product. Those
        // products are added to the result rather than replacing it, because the
        // same term might legitimately match both a SKU and a batch.
        Set<Long> searchBatchScope = productsHoldingBatch(branchId, searchBatchLike(filter));

        Long orgId = orgOf(branchId);
        String like = likeOf(f.search());

        Page<PosProductInfo> products = productRepo.findAll(
                (root, query, cb) -> {
                    List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
                    p.add(cb.equal(root.get("status"), "Y"));
                    // Tenant scope, from the JWT — never a parameter. A product
                    // carrying a branch is that pharmacy's; one carrying none is shared.
                    if (orgId != null) p.add(cb.equal(root.get("pidOrg"), orgId));
                    p.add(cb.or(cb.isNull(root.get("pidBranch")),
                                cb.equal(root.get("pidBranch"), branchId)));
                    if (f.categoryId() != null) p.add(cb.equal(root.get("pidCategory"), f.categoryId()));
                    if (f.companyId() != null)  p.add(cb.equal(root.get("pidCompany"), f.companyId()));
                    if (f.productId() != null)  p.add(cb.equal(root.get("pidProduct"), f.productId()));
                    if (like != null) {
                        // name OR sku OR "holds a batch matching this term"
                        List<jakarta.persistence.criteria.Predicate> any = new ArrayList<>();
                        any.add(cb.like(cb.lower(root.get("productName")), like));
                        any.add(cb.like(cb.lower(root.get("sku")), like));
                        if (!searchBatchScope.isEmpty()) {
                            any.add(root.get("pidProduct").in(searchBatchScope));
                        }
                        p.add(cb.or(any.toArray(new jakarta.persistence.criteria.Predicate[0])));
                    }
                    if (batchScope != null) {
                        p.add(batchScope.isEmpty()
                                ? cb.disjunction()
                                : root.get("pidProduct").in(batchScope));
                    }
                    // Only products actually holding stock. `rollups` is keyed
                    // by product and already excludes empty lots, so it is the
                    // authority — no second query needed.
                    if (f.inStockOnly()) {
                        p.add(rollups.isEmpty()
                                ? cb.disjunction()
                                : root.get("pidProduct").in(rollups.keySet()));
                    }
                    return cb.and(p.toArray(new jakarta.persistence.criteria.Predicate[0]));
                }, pageable);

        List<ProductStockRow> rows = products.getContent().stream()
                .map(p -> toProductRow(p, rollups.get(p.getPidProduct())))
                .toList();

        return PageResponse.from(products, rows);
    }

    /* ══════════════════════════════════════════════════════════════════════
       BATCH STOCK
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BatchStockRow> getBatchStock(Long branchId, StockFilter filter,
                                                     boolean includeEmpty, Pageable pageable) {
        Scope scope = resolveScope(branchId, filter);
        if (nothingToShow(scope, filter)) return emptyPage(pageable);

        Page<PosStockBatch> page = batchRepo.findBatchStock(
                branchId, scope.filtering(), scope.ids(), searchBatchLike(filter), batchLike(filter), includeEmpty, pageable);
        return PageResponse.from(page, mapBatches(page.getContent()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BatchStockRow> getExpiringBatches(Long branchId, StockFilter filter,
                                                          int days, Pageable pageable) {
        Scope scope = resolveScope(branchId, filter);
        if (nothingToShow(scope, filter)) return emptyPage(pageable);

        Page<PosStockBatch> page = batchRepo.findExpiringBatches(
                branchId, LocalDate.now().plusDays(Math.max(days, 0)),
                scope.filtering(), scope.ids(), searchBatchLike(filter), batchLike(filter), pageable);
        return PageResponse.from(page, mapBatches(page.getContent()));
    }

    /** An empty page, for when a filter matched no products at all. */
    private static <T> PageResponse<T> emptyPage(Pageable pageable) {
        return PageResponse.of(new org.springframework.data.domain.PageImpl<T>(
                List.of(), pageable, 0));
    }

    /* ══════════════════════════════════════════════════════════════════════
       ALERTS
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public List<StockResponses.SlowMovingRow> getSlowMoving(Long branchId, StockFilter filter, int idleDays) {
        LocalDate today = LocalDate.now();
        int days = Math.max(1, idleDays);
        return batchRepo.findSlowMoving(orgOf(branchId), branchId, today.minusDays(days), companyOf(filter), likeOf(searchOf(filter))).stream()
                .map(r -> {
                    BigDecimal pcs = asDecimal(r[7]);
                    Integer perBox = asInt(r[12]), perStrip = asInt(r[13]);
                    LocalDate expiry = r[6] == null ? null : ((java.sql.Date) r[6]).toLocalDate();
                    LocalDate lastSold = r[10] == null ? null : ((java.sql.Date) r[10]).toLocalDate();
                    LocalDate received = r[11] == null ? null : ((java.sql.Date) r[11]).toLocalDate();
                    LocalDate idleFrom = lastSold != null ? lastSold : received;
                    return StockResponses.SlowMovingRow.builder()
                            .stockId(asLong(r[0])).productId(asLong(r[1])).productName((String) r[2]).sku((String) r[3]).companyName((String) r[4])
                            .batchNo((String) r[5]).expiryDate(expiry)
                            .daysToExpiry(expiry == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(today, expiry))
                            .qtyPcs(pcs).qtyDisplay(StockUnitConverter.describe(pcs, perBox, perStrip))
                            .costPerPcs(asDecimal(r[8])).stockValue(asDecimal(r[9]))
                            .lastSold(lastSold)
                            .daysIdle(idleFrom == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(idleFrom, today))
                            .receivedDate(received)
                            .build();
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductStockRow> getLowStockAlerts(Long branchId, StockFilter filter) {
        return batchRepo.findLowStock(orgOf(branchId), branchId, LocalDate.now(),
                                      companyOf(filter), likeOf(searchOf(filter))).stream()
                .map(r -> {
                    BigDecimal pcs = asDecimal(r[4]);
                    Integer perBox   = asInt(r[6]);
                    Integer perStrip = asInt(r[7]);
                    return ProductStockRow.builder()
                        .productId(asLong(r[0]))
                        .productName((String) r[1])
                        .imgColor((String) r[2])
                        .alertQty(asInt(r[3]))
                        .stockPcs(pcs)
                        .stockValue(asDecimal(r[5]))
                        // An alert that reads "10 pcs" when the shelf holds
                        // most of a box is technically right and operationally
                        // useless — the person restocking counts boxes.
                        .stockBox(StockUnitConverter.fromPcs(pcs, StockUnitConverter.BOX, perBox, perStrip))
                        .stockStrip(StockUnitConverter.fromPcs(pcs, StockUnitConverter.STRIP, perBox, perStrip))
                        .stockDisplay(StockUnitConverter.describe(pcs, perBox, perStrip))
                        .pcsPerBox(perBox)
                        .pcsPerStrip(perStrip)
                        // Averaged: a low-stock product may still hold several
                        // lots bought at different prices.
                        .cost(costBlock(avgCostPerPcs(asDecimal(r[5]), pcs), perBox, perStrip, true))
                        .stockStatus("LOW")
                        .build();
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductStockRow> getOutOfStockAlerts(Long branchId, StockFilter filter) {
        return batchRepo.findOutOfStock(orgOf(branchId), branchId, LocalDate.now(),
                                        companyOf(filter), likeOf(searchOf(filter))).stream()
                .map(r -> ProductStockRow.builder()
                        .productId(asLong(r[0]))
                        .productName((String) r[1])
                        .imgColor((String) r[2])
                        .alertQty(asInt(r[3]))
                        .stockPcs(BigDecimal.ZERO)
                        .stockValue(BigDecimal.ZERO)
                        .stockDisplay("0 pcs")
                        .stockStatus("OUT")
                        .build())
                .toList();
    }

    /* ══════════════════════════════════════════════════════════════════════
       MOVEMENT LEDGER
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MovementRow> getMovementLedger(Long branchId, StockFilter filter,
                                                       LocalDate fromDate, LocalDate toDate,
                                                       Pageable pageable) {
        Scope scope = resolveScope(branchId, filter);
        if (nothingToShow(scope, filter)) return emptyPage(pageable);

        Page<PosStockMovement> page = movementRepo.findLedger(
                branchId, scope.filtering(), scope.ids(), searchBatchLike(filter), batchLike(filter),
                fromDate, toDate, pageable);

        // One lookup for the whole page rather than one per row.
        Map<Long, String> names = productNames(page.getContent().stream()
                .map(PosStockMovement::getPidProduct).collect(Collectors.toSet()));

        // Packing per lot, so a movement is shown in the units the lot is
        // actually packed in rather than in pieces alone.
        Map<Long, PosStockBatch> lots = batchRepo.findAllById(page.getContent().stream()
                        .map(PosStockMovement::getPidStock).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(PosStockBatch::getPidStock, Function.identity(), (a, b) -> a));

        List<MovementRow> rows = page.getContent().stream()
                .map(m -> {
                    PosStockBatch lot = lots.get(m.getPidStock());
                    Integer perBox   = lot != null ? lot.getPcsPerBox() : null;
                    Integer perStrip = lot != null ? lot.getPcsPerStrip() : null;
                    return MovementRow.builder()
                        .movementId(m.getPidMovement())
                        .stockId(m.getPidStock())
                        .productId(m.getPidProduct())
                        .productName(names.get(m.getPidProduct()))
                        .batchNo(m.getBatchNo())
                        .movementType(m.getMovementType())
                        .direction(m.getDirection())
                        .qtyPcs(m.getQtyPcs())
                        .qtyBox(StockUnitConverter.fromPcs(m.getQtyPcs(), StockUnitConverter.BOX, perBox, perStrip))
                        .qtyStrip(StockUnitConverter.fromPcs(m.getQtyPcs(), StockUnitConverter.STRIP, perBox, perStrip))
                        .qtyDisplay(StockUnitConverter.describe(m.getQtyPcs(), perBox, perStrip))
                        .pcsPerBox(perBox)
                        .pcsPerStrip(perStrip)
                        .signedQtyPcs(m.signedQty())
                        .balanceAfter(m.getBalanceAfter())
                        .costPerPcs(m.getCostPerPcs())
                        .cost(costBlock(m.getCostPerPcs(), perBox, perStrip, false))
                        .refType(m.getRefType())
                        .refMst(m.getRefMst())
                        .refDtl(m.getRefDtl())
                        .movementDate(m.getMovementDate())
                        .remarks(m.getRemarks())
                        .insertedDate(m.getInsertedDate())
                        .insertedBy(m.getInsertedBy())
                        .build();
                })
                .toList();

        return PageResponse.from(page, rows);
    }

    /* ══════════════════════════════════════════════════════════════════════
       MOVEMENT SUMMARY — all four document types
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public MovementSummary getMovementSummary(Long branchId, StockFilter filter,
                                              LocalDate fromDate, LocalDate toDate) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now().withDayOfMonth(1);
        LocalDate to   = toDate   != null ? toDate   : LocalDate.now();
        if (to.isBefore(from)) {
            throw new com.friendspharma.app.exception.custom.BusinessException(
                    "The end date is before the start date.",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }

        Scope scope = resolveScope(branchId, filter);
        List<Object[]> raw = nothingToShow(scope, filter) ? List.of()
                : movementRepo.movementSummary(branchId, scope.filtering(), scope.ids(),
                                               searchBatchLike(filter), batchLike(filter), from, to);

        Set<Long> ids = raw.stream().map(r -> asLong(r[0])).collect(Collectors.toSet());
        Map<Long, PosProductInfo> products = productRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(PosProductInfo::getPidProduct, Function.identity(), (a, b) -> a));

        // Packing comes from the lots, so a report describes the stock actually
        // held rather than the product's current pack size.
        Map<Long, Rollup> rollups = batchRepo.stockRollupByProduct(branchId).stream()
                .map(Rollup::of)
                .collect(Collectors.toMap(Rollup::productId, Function.identity(), (a, b) -> a));

        List<MovementSummaryRow> rows = new ArrayList<>(raw.size());
        BigDecimal tPurch = BigDecimal.ZERO, tSold = BigDecimal.ZERO;
        BigDecimal tPurchRet = BigDecimal.ZERO, tSalesRet = BigDecimal.ZERO;
        BigDecimal tPurchVal = BigDecimal.ZERO, tCostVal = BigDecimal.ZERO;
        long unbalanced = 0;

        for (Object[] r : raw) {
            Long productId0 = asLong(r[0]);
            PosProductInfo p = products.get(productId0);
            Rollup roll = rollups.get(productId0);

            BigDecimal opening   = asDecimal(r[1]);
            BigDecimal purchased = asDecimal(r[2]);
            BigDecimal purchRet  = asDecimal(r[3]);
            BigDecimal sold      = asDecimal(r[4]);
            BigDecimal salesRet  = asDecimal(r[5]);
            BigDecimal adjusted  = asDecimal(r[6]);
            BigDecimal closing   = asDecimal(r[7]);
            BigDecimal purchVal  = asDecimal(r[8]);
            BigDecimal costVal   = asDecimal(r[9]);

            // The row proves itself: closing is read from the movements
            // independently, not inferred, so a mismatch is a real defect
            // surfacing rather than an arithmetic slip being hidden.
            BigDecimal derived = opening.add(purchased).subtract(purchRet)
                                        .subtract(sold).add(salesRet).add(adjusted);
            boolean balanced = derived.compareTo(closing) == 0;
            if (!balanced) unbalanced++;

            Integer perBox   = roll != null ? roll.pcsPerBox() : null;
            Integer perStrip = roll != null ? roll.pcsPerStrip() : null;

            rows.add(MovementSummaryRow.builder()
                    .productId(productId0)
                    .productName(p != null ? p.getProductName() : null)
                    .sku(p != null ? p.getSku() : null)
                    .openingPcs(opening)
                    .purchasedPcs(purchased)
                    .purchaseReturnedPcs(purchRet)
                    .soldPcs(sold)
                    .salesReturnedPcs(salesRet)
                    .adjustedPcs(adjusted)
                    .closingPcs(closing)
                    .openingDisplay(StockUnitConverter.describe(opening, perBox, perStrip))
                    .closingDisplay(StockUnitConverter.describe(closing, perBox, perStrip))
                    .openingBox(StockUnitConverter.fromPcs(opening, StockUnitConverter.BOX, perBox, perStrip))
                    .openingStrip(StockUnitConverter.fromPcs(opening, StockUnitConverter.STRIP, perBox, perStrip))
                    .purchasedBox(StockUnitConverter.fromPcs(purchased, StockUnitConverter.BOX, perBox, perStrip))
                    .soldBox(StockUnitConverter.fromPcs(sold, StockUnitConverter.BOX, perBox, perStrip))
                    .closingBox(StockUnitConverter.fromPcs(closing, StockUnitConverter.BOX, perBox, perStrip))
                    .closingStrip(StockUnitConverter.fromPcs(closing, StockUnitConverter.STRIP, perBox, perStrip))
                    .pcsPerBox(perBox)
                    .pcsPerStrip(perStrip)
                    .purchaseValue(purchVal)
                    .salesCostValue(costVal)
                    // What was bought at, over this period: purchase value over
                    // the pieces purchased. Falls back to the cost of the stock
                    // on hand when nothing was bought in the window, so the
                    // column is not blank on a month of pure selling.
                    .cost(costBlock(
                            purchased.compareTo(BigDecimal.ZERO) > 0
                                    ? avgCostPerPcs(purchVal, purchased)
                                    : (roll != null ? avgCostPerPcs(roll.stockValue(), roll.stockPcs()) : null),
                            perBox, perStrip, true))
                    .balanced(balanced)
                    .prices(priceBlock(p, perBox, perStrip))
                    .build());

            tPurch = tPurch.add(purchased);
            tSold = tSold.add(sold);
            tPurchRet = tPurchRet.add(purchRet);
            tSalesRet = tSalesRet.add(salesRet);
            tPurchVal = tPurchVal.add(purchVal);
            tCostVal = tCostVal.add(costVal);
        }

        if (unbalanced > 0) {
            log.error("Movement summary for branch {} has {} product(s) whose closing balance does not "
                    + "match their own movements. Investigate before trusting the period figures.",
                    branchId, unbalanced);
        }

        return MovementSummary.builder()
                .branchId(branchId).fromDate(from).toDate(to)
                .totalPurchasedPcs(tPurch).totalSoldPcs(tSold)
                .totalPurchaseReturnedPcs(tPurchRet).totalSalesReturnedPcs(tSalesRet)
                .totalPurchaseValue(tPurchVal).totalSalesCostValue(tCostVal)
                .productCount(rows.size()).unbalancedCount(unbalanced)
                .rows(rows)
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       ACTUAL PROFIT
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public ProfitSummary getProfit(Long branchId, StockFilter filter,
                                   LocalDate fromDate, LocalDate toDate) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now().withDayOfMonth(1);
        LocalDate to   = toDate   != null ? toDate   : LocalDate.now();
        if (to.isBefore(from)) {
            throw new BusinessException("The end date is before the start date.",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }

        Scope scope = resolveScope(branchId, filter);
        List<Object[]> raw = nothingToShow(scope, filter) ? List.of()
                : movementRepo.profitByProduct(branchId, scope.filtering(), scope.ids(),
                                               searchBatchLike(filter), batchLike(filter), from, to);

        Set<Long> ids = raw.stream().map(r -> asLong(r[0])).collect(Collectors.toSet());
        Map<Long, PosProductInfo> products = productRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(PosProductInfo::getPidProduct, Function.identity(), (a, b) -> a));
        Map<Long, Rollup> rollups = batchRepo.stockRollupByProduct(branchId).stream()
                .map(Rollup::of)
                .collect(Collectors.toMap(Rollup::productId, Function.identity(), (a, b) -> a));

        List<ProfitRow> rows = new ArrayList<>(raw.size());
        BigDecimal tRevenue = BigDecimal.ZERO, tCost = BigDecimal.ZERO, tNetPcs = BigDecimal.ZERO;
        long belowCost = 0;

        for (Object[] r : raw) {
            Long pid = asLong(r[0]);
            PosProductInfo p = products.get(pid);
            Rollup roll = rollups.get(pid);

            BigDecimal soldPcs     = asDecimal(r[1]);
            BigDecimal returnedPcs = asDecimal(r[2]);
            BigDecimal netPcs      = asDecimal(r[3]);
            BigDecimal revenue     = asDecimal(r[4]);
            BigDecimal cost        = asDecimal(r[5]);
            BigDecimal profit      = revenue.subtract(cost);

            Integer perBox   = roll != null ? roll.pcsPerBox() : null;
            Integer perStrip = roll != null ? roll.pcsPerStrip() : null;

            // Margin is against revenue, not cost. A 100 cost sold at 150 is a
            // 33% margin, not 50% — mark-up and margin are different numbers and
            // reporting one as the other overstates every line.
            BigDecimal margin = revenue.compareTo(BigDecimal.ZERO) == 0 ? null
                    : profit.multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP);

            BigDecimal avgSale = netPcs.compareTo(BigDecimal.ZERO) == 0 ? null
                    : revenue.divide(netPcs, 4, RoundingMode.HALF_UP);
            BigDecimal avgCost = netPcs.compareTo(BigDecimal.ZERO) == 0 ? null
                    : cost.divide(netPcs, 4, RoundingMode.HALF_UP);

            boolean below = profit.compareTo(BigDecimal.ZERO) < 0;
            if (below) belowCost++;

            rows.add(ProfitRow.builder()
                    .productId(pid)
                    .productName(p != null ? p.getProductName() : null)
                    .sku(p != null ? p.getSku() : null)
                    .soldPcs(soldPcs).returnedPcs(returnedPcs).netPcs(netPcs)
                    .netBox(StockUnitConverter.fromPcs(netPcs, StockUnitConverter.BOX, perBox, perStrip))
                    .netStrip(StockUnitConverter.fromPcs(netPcs, StockUnitConverter.STRIP, perBox, perStrip))
                    .netDisplay(StockUnitConverter.describe(netPcs, perBox, perStrip))
                    .pcsPerBox(perBox).pcsPerStrip(perStrip)
                    .revenue(revenue).cost(cost).grossProfit(profit).marginPct(margin)
                    .avgSalePricePerPcs(avgSale).avgCostPerPcs(avgCost)
                    // The buy price actually realised on what was sold — cost
                    // over pieces — scaled to box and strip. This is what the
                    // margin was earned against, not the current list cost.
                    .costPrice(costBlock(avgCost, perBox, perStrip, true))
                    .soldBelowCost(below)
                    .build());

            tRevenue = tRevenue.add(revenue);
            tCost = tCost.add(cost);
            tNetPcs = tNetPcs.add(netPcs);
        }

        BigDecimal totalProfit = tRevenue.subtract(tCost);
        BigDecimal totalMargin = tRevenue.compareTo(BigDecimal.ZERO) == 0 ? null
                : totalProfit.multiply(HUNDRED).divide(tRevenue, 2, RoundingMode.HALF_UP);

        return ProfitSummary.builder()
                .branchId(branchId).fromDate(from).toDate(to)
                .totalRevenue(tRevenue).totalCost(tCost)
                .totalGrossProfit(totalProfit).totalMarginPct(totalMargin)
                .totalNetPcs(tNetPcs)
                .productCount(rows.size()).belowCostCount(belowCost)
                .rows(rows)
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       ALLOCATION PREVIEW
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public StockAllocation previewAllocation(Long branchId, Long productId,
                                             BigDecimal qty, String unit) {
        return stockService.preview(branchId, productId, qty, unit);
    }

    /* ══════════════════════════════════════════════════════════════════════
       RECONCILIATION
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationRow> reconcile() {
        List<ReconciliationRow> divergent = movementRepo.findBalanceDivergence().stream()
                .map(r -> {
                    BigDecimal stored = asDecimal(r[4]);
                    BigDecimal ledger = asDecimal(r[5]);
                    return ReconciliationRow.builder()
                            .stockId(asLong(r[0]))
                            .branchId(asLong(r[1]))
                            .productId(asLong(r[2]))
                            .batchNo((String) r[3])
                            .storedBalance(stored)
                            .ledgerBalance(ledger)
                            .difference(stored.subtract(ledger))
                            .build();
                })
                .toList();

        if (!divergent.isEmpty()) {
            // Loud on purpose. A divergence means a balance moved without a
            // movement row, which is the one thing this design is meant to
            // prevent — it should never be discovered from a quiet report.
            log.error("Stock reconciliation found {} batch(es) whose balance disagrees with their "
                    + "movement history. The movement log is authoritative; investigate before trusting "
                    + "any stock figure. Batches: {}", divergent.size(),
                    divergent.stream().map(ReconciliationRow::getStockId).toList());
        }
        return divergent;
    }

    /* ══════════════════════════════════════════════════════════════════════
       POS CART
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public Long createTransactionFromCart(com.friendspharma.app.entity.PosCart cart,
                                          String paymentType, String remarks, Long userId) {
        // Unchanged from before this rewrite: POS checkout is not built. It is
        // left throwing rather than silently returning null, which would look
        // like a successful sale that moved no stock.
        throw new UnsupportedOperationException("createTransactionFromCart not yet implemented");
    }

    /* ══════════════════════════════════════════════════════════════════════
       MAPPING
       ══════════════════════════════════════════════════════════════════════ */

    private List<BatchStockRow> mapBatches(List<PosStockBatch> batches) {
        // The whole page's products in one query. Pricing needs the full entity,
        // not just the name, and fetching per row would be an N+1 on a screen
        // that shows fifty lots at a time.
        Map<Long, PosProductInfo> products = productRepo.findAllById(
                        batches.stream().map(PosStockBatch::getPidProduct).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(PosProductInfo::getPidProduct, Function.identity(), (a, b) -> a));
        LocalDate today = LocalDate.now();

        return batches.stream().map(b -> {
            Integer daysLeft = b.getExpiryDate() == null ? null
                    : (int) ChronoUnit.DAYS.between(today, b.getExpiryDate());
            String expiryStatus = daysLeft == null ? "OK"
                    : daysLeft < 0 ? "EXPIRED"
                    : daysLeft <= NEAR_EXPIRY_DAYS ? "NEAR_EXPIRY" : "OK";

            BigDecimal qty = nvl(b.getQtyPcs());
            return BatchStockRow.builder()
                    .stockId(b.getPidStock())
                    .branchId(b.getPidBranch())
                    .productId(b.getPidProduct())
                    .productName(products.get(b.getPidProduct()) != null ? products.get(b.getPidProduct()).getProductName() : null)
                    .sku(products.get(b.getPidProduct()) != null ? products.get(b.getPidProduct()).getSku() : null)
                    .batchNo(b.getBatchNo())
                    .expiryDate(b.getExpiryDate())
                    .daysToExpiry(daysLeft)
                    .expiryStatus(expiryStatus)
                    .qtyPcs(qty)
                    .qtyBox(StockUnitConverter.fromPcs(qty, StockUnitConverter.BOX,
                            b.getPcsPerBox(), b.getPcsPerStrip()))
                    .qtyStrip(StockUnitConverter.fromPcs(qty, StockUnitConverter.STRIP,
                            b.getPcsPerBox(), b.getPcsPerStrip()))
                    .qtyDisplay(StockUnitConverter.describe(qty, b.getPcsPerBox(), b.getPcsPerStrip()))
                    .pcsPerBox(b.getPcsPerBox())
                    .pcsPerStrip(b.getPcsPerStrip())
                    .costPerPcs(b.getCostPerPcs())
                    .mrpPrice(b.getMrpPrice())
                    // One lot, one cost — never averaged.
                    .cost(costBlock(b.getCostPerPcs(), b.getPcsPerBox(), b.getPcsPerStrip(), false))
                    .stockValue(qty.multiply(nvl(b.getCostPerPcs())))
                    .receivedPcs(b.getReceivedPcs())
                    .issuedPcs(b.getIssuedPcs())
                    .receivedDate(b.getReceivedDate())
                    .status(b.getStatus())
                    .prices(priceBlock(products.get(b.getPidProduct()), b.getPcsPerBox(), b.getPcsPerStrip()))
                    .build();
        }).toList();
    }

    private ProductStockRow toProductRow(PosProductInfo p, Rollup rollup) {
        BigDecimal pcs   = rollup != null ? rollup.stockPcs() : BigDecimal.ZERO;
        BigDecimal value = rollup != null ? rollup.stockValue() : BigDecimal.ZERO;

        // Packing is read from the lots themselves, not from current master
        // data, so the display matches the stock actually held.
        Integer pcsPerBox   = rollup != null ? rollup.pcsPerBox() : null;
        Integer pcsPerStrip = rollup != null ? rollup.pcsPerStrip() : null;

        String status = pcs.compareTo(BigDecimal.ZERO) <= 0 ? "OUT"
                : (p.getAlertQty() != null && pcs.compareTo(BigDecimal.valueOf(p.getAlertQty())) <= 0)
                        ? "LOW" : "OK";

        return ProductStockRow.builder()
                .productId(p.getPidProduct())
                .productName(p.getProductName())
                .sku(p.getSku())
                .barcode(p.getBarcode())
                // Declared on the DTO from the start but never filled in, so
                // every consumer that showed a company got a blank.
                .companyName(companyName(p.getPidCompany()))
                .imgColor(p.getImgColor())
                .productImageUrl(imageStorage.toPublicUrl(p.getProductImage()))
                .stockPcs(pcs)
                .stockBox(StockUnitConverter.fromPcs(pcs, StockUnitConverter.BOX, pcsPerBox, pcsPerStrip))
                .stockStrip(StockUnitConverter.fromPcs(pcs, StockUnitConverter.STRIP, pcsPerBox, pcsPerStrip))
                .stockDisplay(StockUnitConverter.describe(pcs, pcsPerBox, pcsPerStrip))
                .pcsPerBox(pcsPerBox)
                .pcsPerStrip(pcsPerStrip)
                .stockValue(value)
                .cost(costBlock(avgCostPerPcs(value, pcs), pcsPerBox, pcsPerStrip,
                                (rollup != null ? rollup.batchCount() : 0) > 1))
                .alertQty(p.getAlertQty())
                .stockStatus(status)
                .batchCount(rollup != null ? rollup.batchCount() : 0)
                .earliestExpiry(rollup != null ? rollup.earliestExpiry() : null)
                .prices(priceBlock(p, pcsPerBox, pcsPerStrip))
                .build();
    }

    /**
     * Landed buy price per box, strip and piece.
     *
     * <p>Scales the piece cost up by the packing frozen on the lot, so the box
     * figure is what a box of <em>this</em> delivery actually cost — not the
     * product's current list price, which is a different number and often a
     * stale one.
     *
     * <p>Returns null when there is no cost to show. A zero would read as "this
     * was free" rather than "nobody recorded what it cost".
     *
     * @param averaged true when the figure spans several lots, so the UI can
     *                 label it rather than presenting an average as a fact
     */
    private CostBlock costBlock(BigDecimal costPerPcs, Integer pcsPerBox,
                                Integer pcsPerStrip, boolean averaged) {
        if (costPerPcs == null) return null;

        return CostBlock.builder()
                .costPerPcs(costPerPcs.setScale(4, RoundingMode.HALF_UP))
                .costPerStrip(scaleCost(costPerPcs, pcsPerStrip))
                .costPerBox(scaleCost(costPerPcs, pcsPerBox))
                .pcsPerBox(pcsPerBox)
                .pcsPerStrip(pcsPerStrip)
                .averaged(averaged)
                .build();
    }

    /** Piece cost × pieces in the unit, or null when that packing is unset. */
    private static BigDecimal scaleCost(BigDecimal costPerPcs, Integer pcsPerUnit) {
        if (costPerPcs == null || pcsPerUnit == null || pcsPerUnit <= 0) return null;
        return costPerPcs.multiply(BigDecimal.valueOf(pcsPerUnit)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Weighted average piece cost across lots: total value ÷ total pieces.
     * Null when there is no stock to average over.
     */
    private static BigDecimal avgCostPerPcs(BigDecimal stockValue, BigDecimal stockPcs) {
        if (stockValue == null || stockPcs == null || stockPcs.compareTo(BigDecimal.ZERO) <= 0) return null;
        return stockValue.divide(stockPcs, 6, RoundingMode.HALF_UP);
    }

    /**
     * Pieces per box and per strip as the product itself is configured.
     *
     * @return {@code {pcsPerBox, pcsPerStrip}}, either zero when not configured
     */
    private int[] productPacking(PosProductInfo p) {
        if (p.getPidBox() == null) return new int[]{0, 0};
        var box = boxRepo.findById(p.getPidBox()).orElse(null);
        if (box == null || box.getNoOfLeaf() == null || box.getPidLeaf() == null) return new int[]{0, 0};
        var leaf = leafRepo.findById(box.getPidLeaf()).orElse(null);
        if (leaf == null || leaf.getNoOfPcs() == null) return new int[]{0, 0};
        return new int[]{ box.getNoOfLeaf() * leaf.getNoOfPcs(), leaf.getNoOfPcs() };
    }

    /**
     * The six sale prices for a product, against MRP apportioned per unit.
     *
     * <p>Packing is passed in from the lots rather than read off the product, so
     * the prices shown against stock describe the stock actually held.
     */
    private UnitPriceBlock priceBlock(PosProductInfo p, Integer pcsPerBox, Integer pcsPerStrip) {
        if (p == null) return null;

        /* The lot's own packing describes the stock actually held and is used
           where it exists. Where it does not, the product's is better than
           nothing: the Sales page prices from the product, so without this the
           same product is sellable there and refused here, which reads as the
           till being broken rather than as a lot missing a pack size.

           Nothing is invented — if neither carries a pack size the unit stays
           unpriced, because a missing pack size is unknown, not "one piece". */
        if (pcsPerBox == null || pcsPerStrip == null) {
            int[] fromProduct = productPacking(p);
            if (pcsPerBox   == null) pcsPerBox   = fromProduct[0] > 0 ? fromProduct[0] : null;
            if (pcsPerStrip == null) pcsPerStrip = fromProduct[1] > 0 ? fromProduct[1] : null;
        }

        // Sale-side: an unset percentage is no discount, not "unsellable". A
        // product nobody has set a Sales% on still has an MRP and is still on
        // the shelf — pricing it at null took it out of the till catalogue.
        var up = SalesPriceCalculator.unitPricesForSale(
                p.getMrpPrice(), pcsPerBox, pcsPerStrip,
                p.getSalesPer(), p.getSalesPerLeaf(), p.getSalesPerPcs(),
                p.getSalesPerSpc(), p.getSalesPerSpcLeaf(), p.getSalesPerSpcRet());

        return UnitPriceBlock.builder()
                .boxMrp(up.boxMrp()).stripMrp(up.stripMrp()).pcsMrp(up.pcsMrp())
                .boxPrice(up.boxPrice()).stripPrice(up.stripPrice()).pcsPrice(up.pcsPrice())
                .specialBoxPrice(up.specialBoxPrice())
                .specialStripPrice(up.specialStripPrice())
                .specialPcsPrice(up.specialPcsPrice())
                .boxPct(p.getSalesPer()).stripPct(p.getSalesPerLeaf()).pcsPct(p.getSalesPerPcs())
                .specialBoxPct(p.getSalesPerSpc())
                .specialStripPct(p.getSalesPerSpcLeaf())
                .specialPcsPct(p.getSalesPerSpcRet())
                .priced(up.anyConfigured())
                .build();
    }

    /** One product's aggregated stock across its lots in a branch. */
    private record Rollup(Long productId, BigDecimal stockPcs, BigDecimal stockValue,
                          long batchCount, LocalDate earliestExpiry,
                          Integer pcsPerBox, Integer pcsPerStrip) {
        static Rollup of(Object[] r) {
            return new Rollup(
                    asLong(r[0]), asDecimal(r[1]), asDecimal(r[2]),
                    r[3] == null ? 0 : ((Number) r[3]).longValue(),
                    (LocalDate) r[4],
                    r.length > 5 ? asInt(r[5]) : null,
                    r.length > 6 ? asInt(r[6]) : null);
        }
    }

    private Map<Long, String> productNames(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return productRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(PosProductInfo::getPidProduct,
                                          PosProductInfo::getProductName, (a, b) -> a));
    }

    /**
     * Org for a branch, taken from the session.
     *
     * <p>Branch and org both come from the same JWT, so resolving the org from
     * the branch id would be a lookup that answers a question already answered.
     */
    private Long orgOf(Long branchId) {
        return com.friendspharma.app.security.SecurityUtils.getCurrentOrgId();
    }

    /* ── Native-result coercion ───────────────────────────────────────────── */

    /**
     * A single-row native projection arrives either as {@code Object[]} or, when
     * some drivers collapse it, wrapped in another array. Both shapes appear
     * depending on the Hibernate version, so both are handled.
     */
    private static Object[] unwrap(Object result) {
        if (result == null) return new Object[0];
        Object[] arr = (Object[]) result;
        if (arr.length > 0 && arr[0] instanceof Object[] inner) return inner;
        return arr;
    }

    private static BigDecimal decimal(Object[] row, int i) {
        return i < row.length ? asDecimal(row[i]) : BigDecimal.ZERO;
    }

    private static long number(Object[] row, int i) {
        return i < row.length && row[i] != null ? ((Number) row[i]).longValue() : 0L;
    }

    private static BigDecimal asDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal d) return d;
        return BigDecimal.valueOf(((Number) v).doubleValue());
    }

    private static Long asLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static Integer asInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
