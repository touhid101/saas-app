package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomPharmacyResponses;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.service.impl.TenantStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pharmacy discovery for the marketplace (docs/ecommerce Phase 2).
 *
 * <p>Reads across organisations — every pharmacy on the platform is a
 * candidate — which is why it lives here and not behind {@code BranchScopeService}:
 * a customer has no organisation. It emits public fields only.
 *
 * <p>A pharmacy is listed when its administrator switched the online store on
 * ({@code ECOM_ENABLED='Y'}), the row is active, and the tenant may trade
 * ({@link TenantStatusService#check}: not suspended, not pending, subscription
 * live). A pharmacy whose subscription lapses disappears from the list the
 * same day, with nothing to switch.
 *
 * <p>Distance is the straight line (Haversine) from the point the caller gave.
 * There are a few hundred pharmacies at most, so the whole set is read and
 * sorted in memory; a spatial index is a later concern, if ever.
 */
@Service
@RequiredArgsConstructor
public class EcomPharmacyService {

    public static final double DEFAULT_RADIUS_KM = 10;
    public static final double MAX_RADIUS_KM = 100;

    private final AllBranchRepository branches;
    private final TenantStatusService tenantStatus;

    @Transactional(readOnly = true)
    public EcomPharmacyResponses.PharmacyPage list(Double lat, Double lng, Double radiusKm, String q, int page, int size) {
        boolean byDistance = lat != null && lng != null;
        double radius = radiusKm == null ? DEFAULT_RADIUS_KM : Math.min(Math.max(radiusKm, 0.5), MAX_RADIUS_KM);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        List<EcomPharmacyResponses.Pharmacy> all = branches.findByEcomEnabledAndStatusOrderByBranchName("Y", "Y").stream()
                .filter(b -> tenantStatus.check(b.getPidBranch()).allowed())
                .filter(b -> needle.isEmpty() || matches(b, needle))
                .map(b -> toDto(b, lat, lng))
                // With a point: only pharmacies inside the search radius, nearest first.
                // Pharmacies without coordinates cannot be placed and are left out of a
                // distance search; without a point everything is listed by name.
                .filter(p -> !byDistance || (p.getDistanceKm() != null && p.getDistanceKm() <= radius))
                .sorted(byDistance
                        ? Comparator.comparing(EcomPharmacyResponses.Pharmacy::getDistanceKm)
                        : Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)))
                .toList();

        int sz = Math.min(Math.max(size, 1), 100);
        int pg = Math.max(page, 0);
        int from = Math.min(pg * sz, all.size());
        int to = Math.min(from + sz, all.size());
        return EcomPharmacyResponses.PharmacyPage.builder()
                .items(all.subList(from, to)).page(pg).size(sz).total(all.size())
                .totalPages(Math.max(1, (int) Math.ceil(all.size() / (double) sz)))
                .byDistance(byDistance)
                .build();
    }

    @Transactional(readOnly = true)
    public EcomPharmacyResponses.Pharmacy get(Long id, Double lat, Double lng) {
        AllBranch b = branches.findById(id)
                .filter(x -> x.isEcomEnabled() && "Y".equals(x.getStatus()))
                .filter(x -> tenantStatus.check(x.getPidBranch()).allowed())
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy", id));
        return toDto(b, lat, lng);
    }

    private static boolean matches(AllBranch b, String needle) {
        return contains(b.getBranchName(), needle) || contains(b.getBranchAddress(), needle)
            || contains(b.getEcomDescription(), needle);
    }

    private static boolean contains(String s, String needle) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static EcomPharmacyResponses.Pharmacy toDto(AllBranch b, Double lat, Double lng) {
        Double distance = null;
        Boolean delivers = null;
        if (lat != null && lng != null && b.getLatitude() != null && b.getLongitude() != null) {
            distance = round1(haversineKm(lat, lng, b.getLatitude().doubleValue(), b.getLongitude().doubleValue()));
            if (b.getEcomDeliveryRadiusKm() != null) {
                delivers = distance <= b.getEcomDeliveryRadiusKm().doubleValue();
            }
        }
        return EcomPharmacyResponses.Pharmacy.builder()
                .id(b.getPidBranch()).name(b.getBranchName()).address(b.getBranchAddress())
                .verified(b.isVerified())
                .phone(b.getBranchPhoneNo()).logoUrl(b.getLogoUrl())
                .description(b.getEcomDescription()).openingHours(b.getEcomOpeningHours())
                .latitude(b.getLatitude()).longitude(b.getLongitude())
                .deliveryRadiusKm(b.getEcomDeliveryRadiusKm()).minOrderAmount(b.getEcomMinOrderAmount())
                .distanceKm(distance).deliversHere(delivers)
                .build();
    }

    /** Great-circle distance in km. */
    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
