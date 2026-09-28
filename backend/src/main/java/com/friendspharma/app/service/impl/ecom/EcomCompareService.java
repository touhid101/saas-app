package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomCatalogueResponses.Item;
import com.friendspharma.app.dto.response.EcomCatalogueResponses.UnitOffer;
import com.friendspharma.app.dto.response.EcomCompareResponses;
import com.friendspharma.app.dto.response.EcomPharmacyResponses;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.util.CustomerPricing;
import com.friendspharma.app.util.StockUnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Price comparison (docs/ecommerce Phase 4): the same medicine at every online
 * pharmacy that stocks it, ranked by what the caller would actually pay.
 *
 * <p>The rows are built by {@link EcomCatalogueService#item}, so a price here
 * is the same number the catalogue, the product page and — at checkout — the
 * till will produce. Nothing is recomputed differently for the comparison.
 *
 * <p>Two pharmacies are compared when they sell the same catalogue entry
 * ({@code PID_GLOBAL_PRODUCT}); where a pharmacy typed the product in itself
 * there is no shared id, and the fall-back is an exact product name, which in
 * this catalogue carries the strength ("Ace 500mg"). The answer says which of
 * the two applied.
 *
 * <p>Ranking is by the price of one unit, with price per piece alongside so a
 * pharmacy selling a different pack size can still be judged. Out of stock
 * sinks to the bottom whatever it charges.
 */
@Service
@RequiredArgsConstructor
public class EcomCompareService {

    public static final String MATCH_GLOBAL = "GLOBAL_PRODUCT";
    public static final String MATCH_NAME   = "PRODUCT_NAME";

    private final PosProductInfoRepository products;
    private final AllBranchRepository branches;
    private final EcomPharmacyService pharmacies;
    private final EcomCatalogueService catalogue;

    @Transactional(readOnly = true)
    public EcomCompareResponses.Comparison compare(Long productId, String unit, Double lat, Double lng, Double radiusKm) {
        String tier = EcomCatalogueService.callerTier();
        CustomerPricing.Tier t = CustomerPricing.tierOf(tier);

        PosProductInfo seed = products.findById(productId)
                .filter(p -> "Y".equals(p.getStatus()) && p.getSupersededBy() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        List<EcomPharmacyResponses.Pharmacy> online = pharmacies.list(lat, lng, radiusKm, null, 0, 500).getItems();
        if (online.isEmpty()) throw new ResourceNotFoundException("Product", productId);
        Map<Long, EcomPharmacyResponses.Pharmacy> byBranch = online.stream()
                .collect(Collectors.toMap(EcomPharmacyResponses.Pharmacy::getId, p -> p, (a, b) -> a, LinkedHashMap::new));
        Map<Long, Long> orgOfBranch = new HashMap<>();
        for (Long id : byBranch.keySet()) branches.findById(id).ifPresent(b -> orgOfBranch.put(id, b.getPidOrg()));

        String matchedBy = seed.getPidGlobalProduct() != null ? MATCH_GLOBAL : MATCH_NAME;
        List<PosProductInfo> same = products.findSameMedicine(
                byBranch.keySet(), new HashSet<>(orgOfBranch.values()),
                seed.getPidGlobalProduct(),
                seed.getProductName() == null ? "" : seed.getProductName().trim().toLowerCase(Locale.ROOT));

        // One priced row per (product, online pharmacy). An org-wide row belongs to every online branch of its org.
        Map<Long, EcomCatalogueService.Packing> packing = new HashMap<>();
        List<Item> items = new ArrayList<>();
        for (PosProductInfo p : same) {
            List<Long> targets = p.getPidBranch() != null
                    ? (byBranch.containsKey(p.getPidBranch()) ? List.of(p.getPidBranch()) : List.<Long>of())
                    : orgOfBranch.entrySet().stream().filter(e -> e.getValue().equals(p.getPidOrg())).map(Map.Entry::getKey).toList();
            for (Long b : targets) items.add(catalogue.item(p, byBranch.get(b), t, tier, packing));
        }
        if (items.isEmpty()) throw new ResourceNotFoundException("Product", productId);

        // Which unit to compare in: the one asked for when the tier may buy it and
        // somebody sells it, else the cheapest unit the seed row leads with.
        List<String> comparable = List.of(StockUnitConverter.BOX, StockUnitConverter.STRIP, StockUnitConverter.PCS).stream()
                .filter(u -> CustomerPricing.allows(t, u))
                .filter(u -> items.stream().anyMatch(i -> offer(i, u) != null && offer(i, u).getPrice() != null))
                .toList();
        String chosen = StockUnitConverter.normalise(unit);
        if (unit == null || !comparable.contains(chosen)) {
            Item seedItem = items.stream().filter(i -> i.getProductId().equals(productId)).findFirst().orElse(items.get(0));
            chosen = comparable.contains(seedItem.getDefaultUnit()) ? seedItem.getDefaultUnit()
                   : comparable.isEmpty() ? StockUnitConverter.PCS : comparable.get(0);
        }
        final String compareUnit = chosen;

        // Rank: in stock first, then price for one unit, then distance.
        List<Item> ranked = items.stream()
                .sorted(Comparator
                        .comparing((Item i) -> inStock(i, compareUnit) ? 0 : 1)
                        .thenComparing(i -> priceOf(i, compareUnit), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(i -> i.getBranchDistanceKm() == null ? Double.MAX_VALUE : i.getBranchDistanceKm()))
                .toList();

        BigDecimal cheapest = ranked.stream().filter(i -> inStock(i, compareUnit)).map(i -> priceOf(i, compareUnit))
                .filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null);
        BigDecimal dearest = ranked.stream().filter(i -> inStock(i, compareUnit)).map(i -> priceOf(i, compareUnit))
                .filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
        Double nearest = ranked.stream().map(Item::getBranchDistanceKm).filter(Objects::nonNull).min(Double::compareTo).orElse(null);

        List<EcomCompareResponses.Choice> choices = new ArrayList<>();
        int rank = 1;
        for (Item i : ranked) {
            UnitOffer o = offer(i, compareUnit);
            BigDecimal price = priceOf(i, compareUnit);
            Integer per = o == null ? null : o.getPcsPerUnit();
            choices.add(EcomCompareResponses.Choice.builder()
                    .rank(rank++)
                    .productId(i.getProductId()).branchId(i.getBranchId()).branchName(i.getBranchName())
                    .branchAddress(byBranch.get(i.getBranchId()).getAddress())
                    .distanceKm(i.getBranchDistanceKm())
                    .deliversHere(byBranch.get(i.getBranchId()).getDeliversHere())
                    .minOrderAmount(byBranch.get(i.getBranchId()).getMinOrderAmount())
                    .openingHours(byBranch.get(i.getBranchId()).getOpeningHours())
                    .unit(compareUnit).pcsPerUnit(per)
                    .mrp(o == null ? null : o.getMrp()).listPrice(o == null ? null : o.getListPrice())
                    .discountPct(o == null ? null : o.getDiscountPct()).price(price)
                    .pricePerPcs(price != null && per != null && per > 0
                            ? price.divide(BigDecimal.valueOf(per), 4, RoundingMode.HALF_UP) : null)
                    .priceDiff(price != null && cheapest != null ? price.subtract(cheapest) : null)
                    .inStock(o == null ? null : o.getInStock()).availablePcs(i.getAvailablePcs())
                    .cheapest(price != null && cheapest != null && price.compareTo(cheapest) == 0 && inStock(i, compareUnit))
                    .nearest(nearest != null && Objects.equals(i.getBranchDistanceKm(), nearest))
                    .build());
        }

        Item head = ranked.stream().filter(i -> i.getProductId().equals(productId)).findFirst().orElse(ranked.get(0));
        return EcomCompareResponses.Comparison.builder()
                .productId(productId).globalProductId(seed.getPidGlobalProduct())
                .name(head.getName()).generic(head.getGeneric()).company(head.getCompany()).category(head.getCategory())
                .imageUrl(head.getImageUrl()).prescription(head.isPrescription())
                .matchedBy(matchedBy).tier(tier).unit(compareUnit).comparableUnits(comparable)
                .pharmacyCount(choices.size())
                .inStockCount((int) ranked.stream().filter(i -> inStock(i, compareUnit)).count())
                .lowestPrice(cheapest).highestPrice(dearest)
                .maxSaving(cheapest != null && dearest != null ? dearest.subtract(cheapest) : null)
                .choices(choices)
                .build();
    }

    private static UnitOffer offer(Item i, String unit) {
        return i.getUnits().stream().filter(u -> u.getUnit().equals(unit)).findFirst().orElse(null);
    }

    private static BigDecimal priceOf(Item i, String unit) {
        UnitOffer o = offer(i, unit);
        return o == null ? null : o.getPrice();
    }

    private static boolean inStock(Item i, String unit) {
        UnitOffer o = offer(i, unit);
        return o != null && o.getPrice() != null && o.getInStock() != null && o.getInStock() > 0;
    }
}
