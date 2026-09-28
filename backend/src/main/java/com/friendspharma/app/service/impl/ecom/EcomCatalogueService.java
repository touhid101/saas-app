package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomCatalogueResponses;
import com.friendspharma.app.dto.response.EcomCatalogueResponses.Item;
import com.friendspharma.app.dto.response.EcomCatalogueResponses.UnitOffer;
import com.friendspharma.app.dto.response.EcomPharmacyResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import com.friendspharma.app.service.interfaces.SalesDiscountService;
import com.friendspharma.app.util.CustomerPricing;
import com.friendspharma.app.util.SalesPriceCalculator;
import com.friendspharma.app.util.StockUnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The marketplace catalogue (docs/ecommerce Phase 3): what is on the shelf at
 * the online pharmacies, at the caller's price.
 *
 * <p>Nothing here is a second engine. Price is {@link SalesPriceCalculator}
 * and {@link CustomerPricing} — the same pure functions the till runs — plus
 * the pharmacy's standing discount rule; stock is the same batch table the
 * till issues from, read the same way (unexpired, unblocked, whole units per
 * lot). The product rows are the pharmacies' own {@code POS_PRODUCT_INFO}.
 *
 * <p>The tier is the signed-in customer's; a guest is priced as a retailer,
 * the most restrictive tier (Phase 0 decision, pending business confirmation).
 * Checkout (Phase 6) reprices everything server-side again: what is shown here
 * is a quote, never an authority.
 */
@Service
@RequiredArgsConstructor
public class EcomCatalogueService {

    /** Stock and price are worked out for at most this many matches before paging. */
    private static final int MAX_MATCHES = 400;

    private final AllBranchRepository branches;
    private final PosProductInfoRepository products;
    private final PosBoxInfoRepository boxes;
    private final PosLeafInfoRepository leaves;
    private final PosStockBatchRepository batches;
    private final SalesDiscountService discounts;
    private final EcomPharmacyService pharmacies;
    private final com.friendspharma.app.service.impl.ProductImageStorageService images;
    private final com.friendspharma.app.repository.EcomOrderItemRepository orderItems;

    /** How far back "sold" looks: long enough to be a real number, short enough to still be news. */
    private static final int SOLD_DAYS = 7;

    /**
     * Pieces sold at each of these pharmacies this week, keyed {@code branch:product}.
     *
     * <p>One query for a whole page rather than one per card. An empty scope
     * returns an empty map rather than asking for everything.
     */
    private Map<String, Long> soldFor(java.util.Collection<Long> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) return Map.of();
        Map<String, Long> out = new HashMap<>();
        for (Object[] row : orderItems.piecesSoldSince(branchIds, LocalDateTime.now().minusDays(SOLD_DAYS))) {
            out.put(row[0] + ":" + row[1], ((Number) row[2]).longValue());
        }
        return out;
    }

    /**
     * The bar's position: what went, against what went plus what is left.
     *
     * <p>Floored at 3% so a single sale is still visible, and null when nothing
     * sold — a bar sitting at zero would imply a target nobody set.
     */
    private static Integer soldBar(long sold, BigDecimal stock) {
        if (sold <= 0) return null;
        BigDecimal total = (stock == null ? BigDecimal.ZERO : stock).add(BigDecimal.valueOf(sold));
        if (total.compareTo(BigDecimal.ZERO) <= 0) return null;
        int pct = BigDecimal.valueOf(sold).multiply(new BigDecimal("100"))
                .divide(total, 0, java.math.RoundingMode.HALF_UP).intValue();
        return Math.max(3, Math.min(100, pct));
    }

    /* ── search ─────────────────────────────────────────────────────────── */

    /** The pre-existing four-argument shape, for callers that do not filter by maker or choose an order. */
    @Transactional(readOnly = true)
    public EcomCatalogueResponses.Page search(String q, Long branchId, String category, boolean inStockOnly,
                                              Double lat, Double lng, Double radiusKm, int page, int size) {
        return search(q, branchId, category, null, null, null, inStockOnly, lat, lng, radiusKm, page, size);
    }

    /**
     * @param company one manufacturer's name, or null for all of them
     * @param subCategory one sub-category's name, or null for all of them
     * @param sort    "new" (most recently added first), "price-asc", "price-desc",
     *                "discount", or null for the default: in stock, then nearest,
     *                then by name — the order a customer scans in
     */
    @Transactional(readOnly = true)
    public EcomCatalogueResponses.Page search(String q, Long branchId, String category, String company,
                                              String subCategory, String sort,
                                              boolean inStockOnly,
                                              Double lat, Double lng, Double radiusKm, int page, int size) {
        String tier = callerTier();
        CustomerPricing.Tier t = CustomerPricing.tierOf(tier);

        // The pharmacies in scope: one, or every online pharmacy (within the radius when a point is given).
        List<EcomPharmacyResponses.Pharmacy> scope = branchId != null
                ? List.of(pharmacies.get(branchId, lat, lng))
                : pharmacies.list(lat, lng, radiusKm, null, 0, 500).getItems();
        if (scope.isEmpty()) return emptyPage(page, size, tier);
        Map<Long, EcomPharmacyResponses.Pharmacy> byBranch = scope.stream()
                .collect(Collectors.toMap(EcomPharmacyResponses.Pharmacy::getId, p -> p, (a, b) -> a, LinkedHashMap::new));
        Map<Long, Long> orgOfBranch = new HashMap<>();
        for (Long id : byBranch.keySet()) branches.findById(id).ifPresent(b -> orgOfBranch.put(id, b.getPidOrg()));

        /* What has sold at these pharmacies this week — one query for the page,
           handed to every card built below. */
        Map<String, Long> sold = soldFor(byBranch.keySet());

        String needle = q == null ? "" : q.trim();
        List<PosProductInfo> matches = products.searchForMarketplace(byBranch.keySet(), new HashSet<>(orgOfBranch.values()), needle, category == null ? "" : category.trim());

        // Expand: a branch product to its branch; an org-wide (null-branch) product to every online branch of its org.
        List<Item> items = new ArrayList<>();
        Map<Long, Packing> packingCache = new HashMap<>();
        int considered = 0;
        for (PosProductInfo p : matches) {
            List<Long> targets = p.getPidBranch() != null
                    ? (byBranch.containsKey(p.getPidBranch()) ? List.of(p.getPidBranch()) : List.of())
                    : orgOfBranch.entrySet().stream().filter(e -> e.getValue().equals(p.getPidOrg())).map(Map.Entry::getKey).toList();
            for (Long b : targets) {
                if (considered++ >= MAX_MATCHES) break;
                Item it = item(p, byBranch.get(b), t, tier, packingCache, sold);
                if (inStockOnly && !it.isInStock()) continue;
                items.add(it);
            }
        }
        /* A maker is filtered here rather than in the query: the matched set is
           already capped and in memory, and the facet below must count what the
           customer can actually see. */
        if (company != null && !company.isBlank()) {
            String want = company.trim();
            items.removeIf(i -> i.getCompany() == null || !i.getCompany().equalsIgnoreCase(want));
        }
        if (subCategory != null && !subCategory.isBlank()) {
            String want = subCategory.trim();
            items.removeIf(i -> i.getSubCategory() == null || !i.getSubCategory().equalsIgnoreCase(want));
        }

        // In-stock first, then nearest pharmacy, then name — the order a customer scans in.
        Comparator<Item> scanOrder = Comparator.comparing((Item i) -> !i.isInStock())
                .thenComparing(i -> i.getBranchDistanceKm() == null ? Double.MAX_VALUE : i.getBranchDistanceKm())
                .thenComparing(i -> i.getName().toLowerCase(Locale.ROOT));

        /* Every other order still puts what is in stock first: a cheapest-first
           list led by things nobody can buy is a list of prices, not of offers. */
        Comparator<Item> order = switch (sort == null ? "" : sort) {
            case "new"        -> Comparator.comparing((Item i) -> !i.isInStock())
                                           .thenComparing(Item::getAddedAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case "price-asc"  -> Comparator.comparing((Item i) -> !i.isInStock()).thenComparing(this::quoted);
            case "price-desc" -> Comparator.comparing((Item i) -> !i.isInStock()).thenComparing(this::quoted, Comparator.reverseOrder());
            case "discount"   -> Comparator.comparing((Item i) -> !i.isInStock()).thenComparing(this::off, Comparator.reverseOrder());
            default           -> scanOrder;
        };
        items.sort(order);

        // Categories are per pharmacy in the ERP, so the same name appears under several ids; the facet is by name.
        List<EcomCatalogueResponses.Facet> categories = facet(items, i -> i.getCategory() == null ? null : i.getCategory().trim().toLowerCase(Locale.ROOT), Item::getCategory);
        List<EcomCatalogueResponses.Facet> pharms = facet(items, Item::getBranchId, Item::getBranchName);
        /* Manufacturers, counted the same way and from the same rows, so the
           brand rail on the storefront leads somewhere that has stock. */
        List<EcomCatalogueResponses.Facet> makers = facet(items,
                i -> i.getCompany() == null ? null : i.getCompany().trim().toLowerCase(Locale.ROOT), Item::getCompany);
        /* Sub-categories, counted the same way. Usually short: this master
           keeps them optional and few rows carry one, which is why the mega
           menu treats an empty list as a reason to show makers instead. */
        List<EcomCatalogueResponses.Facet> subs = facet(items,
                i -> i.getSubCategory() == null ? null : i.getSubCategory().trim().toLowerCase(Locale.ROOT),
                Item::getSubCategory);

        int sz = Math.min(Math.max(size, 1), 100), pg = Math.max(page, 0);
        int from = Math.min(pg * sz, items.size()), to = Math.min(from + sz, items.size());
        return EcomCatalogueResponses.Page.builder()
                .items(items.subList(from, to)).page(pg).size(sz).total(items.size())
                .totalPages(Math.max(1, (int) Math.ceil(items.size() / (double) sz)))
                .tier(tier).categories(categories).pharmacies(pharms)
                .companies(makers).subCategories(subs)
                .build();
    }

    /** One product at its pharmacy, priced for the caller. */
    @Transactional(readOnly = true)
    public Item get(Long productId, Double lat, Double lng) {
        return get(productId, null, lat, lng);
    }

    /**
     * One product, <b>at a named pharmacy</b>.
     *
     * <p>The branch matters and used to be guessed. An org-wide row — one with
     * no {@code PID_BRANCH} — is listed at every online branch of its
     * organisation, each with that counter's own stock: product 9 is sixteen
     * pieces at one pharmacy and five thousand at another. Answering without a
     * branch picked "the first online branch of the org", so a customer who
     * tapped the card showing 5,000 landed on a page showing 16, and the cart
     * would have gone to the wrong pharmacy.
     *
     * <p>Now the caller says which, and the same ownership rule
     * {@link #quoteAt} uses decides whether that pharmacy may sell it. Omitting
     * it keeps the old behaviour, because a link with no branch has to answer
     * something.
     */
    @Transactional(readOnly = true)
    public Item get(Long productId, Long wantBranch, Double lat, Double lng) {
        String tier = callerTier();
        PosProductInfo p = products.findById(productId)
                .filter(x -> "Y".equals(x.getStatus()) && x.getSupersededBy() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        Long branchId;
        if (wantBranch != null) {
            boolean belongs = p.getPidBranch() != null
                    ? p.getPidBranch().equals(wantBranch)
                    : branches.findById(wantBranch).map(b -> b.getPidOrg().equals(p.getPidOrg())).orElse(false);
            if (!belongs) throw new ResourceNotFoundException("Product", productId);
            branchId = wantBranch;
        } else {
            // Its own branch, or (for an org-wide row) the first online branch of its org.
            branchId = p.getPidBranch();
            if (branchId == null) {
                branchId = branches.findByEcomEnabledAndStatusOrderByBranchName("Y", "Y").stream()
                        .filter(b -> b.getPidOrg().equals(p.getPidOrg())).map(AllBranch::getPidBranch).findFirst().orElse(null);
            }
        }
        if (branchId == null) throw new ResourceNotFoundException("Product", productId);
        EcomPharmacyResponses.Pharmacy ph = pharmacies.get(branchId, lat, lng);   // 404 when the pharmacy is not online
        return item(p, ph, CustomerPricing.tierOf(tier), tier, new HashMap<>(), soldFor(List.of(branchId)));
    }

    /**
     * One product priced at one named pharmacy — what the cart and (Phase 6)
     * checkout ask for, where the pharmacy is already decided.
     *
     * <p>Refuses when the row does not belong to that pharmacy: a branch
     * product belongs to its own branch, an org-wide row to every online
     * branch of its organisation, and nothing else may be sold as if it did.
     */
    @Transactional(readOnly = true)
    public Item quoteAt(PosProductInfo p, Long branchId) {
        EcomPharmacyResponses.Pharmacy ph = pharmacies.get(branchId, null, null);
        boolean belongs = p.getPidBranch() != null
                ? p.getPidBranch().equals(branchId)
                : branches.findById(branchId).map(b -> b.getPidOrg().equals(p.getPidOrg())).orElse(false);
        if (!belongs) throw new ResourceNotFoundException("Product", p.getPidProduct());
        String tier = callerTier();
        return item(p, ph, CustomerPricing.tierOf(tier), tier, new HashMap<>(), soldFor(List.of(branchId)));
    }

    /* ── one row ────────────────────────────────────────────────────────── */

    /** One priced row. Package-visible so the comparison (Phase 4) builds rows the same way. */
    Item item(PosProductInfo p, EcomPharmacyResponses.Pharmacy ph, CustomerPricing.Tier t, String tier,
                      Map<Long, Packing> packingCache) {
        return item(p, ph, t, tier, packingCache, Map.of());
    }

    Item item(PosProductInfo p, EcomPharmacyResponses.Pharmacy ph, CustomerPricing.Tier t, String tier,
                      Map<Long, Packing> packingCache, Map<String, Long> sold) {
        Packing pk = packingCache.computeIfAbsent(p.getPidProduct(), id -> packingOf(p));
        SalesPriceCalculator.UnitPrices prices = SalesPriceCalculator.unitPricesForSale(
                p.getMrpPrice(), pk.pcsPerBox, pk.pcsPerStrip,
                p.getSalesPer(), p.getSalesPerLeaf(), p.getSalesPerPcs(),
                p.getSalesPerSpc(), p.getSalesPerSpcLeaf(), p.getSalesPerSpcRet());

        // Live stock: the lots the till would issue from, counted in whole units per lot.
        List<PosStockBatch> lots = batches.findAllocatableBatches(ph.getId(), p.getPidProduct(), LocalDate.now(), false);
        BigDecimal pcs = lots.stream().map(b -> nvl(b.getQtyPcs())).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<UnitOffer> units = new ArrayList<>();
        String defaultUnit = null;
        BigDecimal bestPerPcs = null;
        for (String u : List.of(StockUnitConverter.BOX, StockUnitConverter.STRIP, StockUnitConverter.PCS)) {
            Integer per = StockUnitConverter.factorFor(u, pk.pcsPerBox, pk.pcsPerStrip);
            boolean allowed = CustomerPricing.allows(t, u);
            BigDecimal list = CustomerPricing.priceFor(t, u, prices);
            String reason = CustomerPricing.unavailableReason(t, u, prices);
            BigDecimal discPct = list == null ? null : nvl(discounts.resolveEffectiveDiscount(p.getPidOrg(), p.getPidProduct(),
                    p.getPidCompany() != null ? p.getPidCompany() : 0L, u));
            BigDecimal price = list == null ? null
                    : list.subtract(list.multiply(discPct).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
            int whole = per == null || per <= 0 ? 0
                    : lots.stream().mapToInt(b -> nvl(b.getQtyPcs()).divideToIntegralValue(BigDecimal.valueOf(per)).intValue()).sum();
            units.add(UnitOffer.builder().unit(u).allowed(allowed).pcsPerUnit(per)
                    .mrp(CustomerPricing.mrpFor(u, prices)).listPrice(list).discountPct(discPct).price(price)
                    .inStock(whole).reason(reason).build());
            if (price != null && whole > 0 && per != null && per > 0) {
                BigDecimal perPcs = price.divide(BigDecimal.valueOf(per), 6, RoundingMode.HALF_UP);
                if (bestPerPcs == null || perPcs.compareTo(bestPerPcs) < 0) { bestPerPcs = perPcs; defaultUnit = u; }
            }
        }
        if (defaultUnit == null) {
            defaultUnit = units.stream().filter(x -> x.getPrice() != null).map(UnitOffer::getUnit).findFirst().orElse(StockUnitConverter.PCS);
        }
        boolean inStock = units.stream().anyMatch(x -> x.getPrice() != null && x.getInStock() > 0);

        return Item.builder()
                .productId(p.getPidProduct()).globalProductId(p.getPidGlobalProduct())
                .name(p.getProductName()).strength(null)
                .generic(p.getGenericInfo() != null ? p.getGenericInfo().getGenericName() : null)
                .company(p.getCompanyInfo() != null ? p.getCompanyInfo().getCompanyName() : null)
                .category(p.getCategoryInfo() != null ? p.getCategoryInfo().getCategoryName() : null)
                .categoryId(p.getPidCategory()).productType(p.getProductType())
                .subCategory(p.getSubCategoryInfo() != null ? p.getSubCategoryInfo().getSubCategoryName() : null)
                .subCategoryId(p.getPidSubCategory())
                .imageUrl(images.toPublicUrl(p.getProductImageUrl()))
                .prescription("Y".equalsIgnoreCase(p.getPrescriptionFlag()))
                .branchId(ph.getId()).branchName(ph.getName())
                .branchVerified(ph.isVerified()).branchDistanceKm(ph.getDistanceKm())
                .pcsPerBox(pk.pcsPerBox).pcsPerStrip(pk.pcsPerStrip)
                .availablePcs(pcs).inStock(inStock).reorderPcs(p.getReorderLevel()).addedAt(p.getCreatedAt())
                .soldPcs(sold.getOrDefault(ph.getId() + ":" + p.getPidProduct(), 0L))
                .soldBarPct(soldBar(sold.getOrDefault(ph.getId() + ":" + p.getPidProduct(), 0L), pcs))
                .tier(tier).defaultUnit(defaultUnit).units(units)
                .build();
    }

    /** The price the card would print: the caller's default unit where they may buy it, else the first they may. */
    private BigDecimal quoted(Item i) {
        return i.getUnits().stream()
                .filter(u -> u.isAllowed() && u.getPrice() != null)
                .filter(u -> u.getUnit().equals(i.getDefaultUnit()))
                .map(UnitOffer::getPrice).findFirst()
                .orElseGet(() -> i.getUnits().stream().filter(u -> u.isAllowed() && u.getPrice() != null)
                        .map(UnitOffer::getPrice).findFirst().orElse(BigDecimal.ZERO));
    }

    /** How far below MRP that same unit is, as a fraction. Zero when it is not reduced. */
    private BigDecimal off(Item i) {
        return i.getUnits().stream()
                .filter(u -> u.isAllowed() && u.getPrice() != null && u.getMrp() != null)
                .filter(u -> u.getMrp().compareTo(BigDecimal.ZERO) > 0)
                .filter(u -> u.getMrp().compareTo(u.getPrice()) > 0)
                .map(u -> u.getMrp().subtract(u.getPrice()).divide(u.getMrp(), 4, java.math.RoundingMode.HALF_UP))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    record Packing(Integer pcsPerBox, Integer pcsPerStrip) {}

    private Packing packingOf(PosProductInfo p) {
        Integer stripsPerBox = null, pcsPerStrip = null;
        if (p.getPidBox() != null) {
            PosBoxInfo box = boxes.findById(p.getPidBox()).orElse(null);
            if (box != null) {
                stripsPerBox = box.getNoOfLeaf();
                if (box.getPidLeaf() != null) {
                    PosLeafInfo leaf = leaves.findById(box.getPidLeaf()).orElse(null);
                    if (leaf != null) pcsPerStrip = leaf.getNoOfPcs();
                }
            }
        }
        Integer pcsPerBox = stripsPerBox != null && pcsPerStrip != null ? stripsPerBox * pcsPerStrip : null;
        return new Packing(pcsPerBox, pcsPerStrip);
    }

    /** The signed-in customer's tier; a guest is a retailer. Suspended or pending customers browse at their stored tier. */
    static String callerTier() {
        EcomCustomerPrincipal c = EcomSecurity.current();
        String t = c == null ? null : c.customerType();
        return EcomCustomer.isKnownType(t) ? t : EcomCustomer.TYPE_RETAILER;
    }

    private static <K> List<EcomCatalogueResponses.Facet> facet(List<Item> items, java.util.function.Function<Item, K> key,
                                                                 java.util.function.Function<Item, String> label) {
        Map<K, EcomCatalogueResponses.Facet> out = new LinkedHashMap<>();
        Map<K, Long> counts = new HashMap<>();
        for (Item i : items) {
            K k = key.apply(i);
            if (k == null) continue;
            counts.merge(k, 1L, Long::sum);
            out.putIfAbsent(k, EcomCatalogueResponses.Facet.builder().id(k instanceof Long l ? l : null).name(label.apply(i)).count(0).build());
        }
        return out.entrySet().stream()
                .map(e -> EcomCatalogueResponses.Facet.builder().id(e.getValue().getId()).name(e.getValue().getName()).count(counts.get(e.getKey())).build())
                .sorted(Comparator.comparing(EcomCatalogueResponses.Facet::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static EcomCatalogueResponses.Page emptyPage(int page, int size, String tier) {
        return EcomCatalogueResponses.Page.builder().items(List.of()).page(Math.max(page, 0)).size(Math.max(size, 1))
                .total(0).totalPages(1).tier(tier).categories(List.of()).pharmacies(List.of())
                .companies(List.of()).subCategories(List.of()).build();
    }

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
