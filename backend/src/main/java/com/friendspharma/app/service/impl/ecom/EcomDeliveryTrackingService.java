package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomTrackingResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Where an order is while it is out, and what happened at the door
 * (docs/ecommerce Phase 10).
 *
 * <p><b>The privacy rules are here, not in the client.</b> A position is
 * accepted only from the driver the order is assigned to, and only while the
 * order is {@code ON_THE_WAY} — nothing follows anyone between deliveries. The
 * customer is given the <em>latest</em> point and nothing else: how far away
 * their medicine is is their business; where the driver has been is not. Once
 * the order is delivered or called off, the tracking answer closes.
 *
 * <p>No ETA is invented. There is no routing service here, so the honest answer
 * is a straight-line distance and how long ago it was reported. A minute-count
 * conjured from a crow-flies kilometre would be a promise the pharmacy has no
 * way to keep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomDeliveryTrackingService {

    /** A reading worse than this is a district, not a position. */
    private static final int USELESS_ACCURACY_M = 2000;

    private final EcomOrderRepository orders;
    private final EcomDeliveryTrackingRepository tracking;
    private final EcomDeliveryProofRepository proofs;
    private final PosTransactionMstRepository invoices;
    private final AllBranchRepository branches;
    private final BranchScopeService branchScope;
    private final ImageStorageService images;
    private final EcomAuditRecorder audit;

    /* ══════════════════════════════════════════════════════════════════════
       THE DRIVER'S SIDE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Records where the order is now.
     *
     * @param accuracyM what the device said about the reading, in metres
     */
    @Transactional
    public EcomTrackingResponses.Ping ping(String orderNo, BigDecimal lat, BigDecimal lng, BigDecimal accuracyM) {
        EcomOrder order = requireDriversOwnOrder(orderNo);

        if (!EcomOrder.ON_THE_WAY.equals(order.getEcomStatus())) {
            throw new BusinessException(
                    "Order " + order.getOrderNo() + " is not out for delivery, so there is nothing to track.",
                    HttpStatus.CONFLICT, "ECOM_NOT_ON_THE_WAY");
        }
        if (lat == null || lng == null) {
            throw new BusinessException("A position needs both a latitude and a longitude.",
                    HttpStatus.BAD_REQUEST, "POSITION_REQUIRED");
        }
        if (accuracyM != null && accuracyM.intValue() > USELESS_ACCURACY_M) {
            /* Refused rather than stored: a reading good to five kilometres
               would move the pin across the city and tell the customer something
               false with the full authority of a map. */
            throw new BusinessException(
                    "That position is only accurate to about " + accuracyM.intValue()
                    + " m, which is too vague to show anybody. Try again where the signal is better.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "POSITION_TOO_VAGUE");
        }

        EcomDeliveryTracking row = EcomDeliveryTracking.builder()
                .pidEcomOrder(order.getPidEcomOrder())
                .pidBranch(order.getPidBranch())
                .pidEmployee(SecurityUtils.getCurrentEmployeeId())
                .latitude(lat).longitude(lng).accuracyM(accuracyM)
                .recordedAt(LocalDateTime.now())
                .build();
        row.setStatus("Y");
        tracking.save(row);

        return EcomTrackingResponses.Ping.builder()
                .orderNo(order.getOrderNo())
                .recordedAt(row.getRecordedAt())
                .kmFromCustomer(distanceToCustomer(order, lat, lng))
                .pointsRecorded(tracking.countByPidEcomOrder(order.getPidEcomOrder()))
                .build();
    }

    /**
     * Writes down what happened at the door.
     *
     * <p>One per order: a delivery is handed over once, and a second row would
     * be a second story about the same doorstep. Recording it again replaces
     * what is there, which is what correcting a mistake looks like.
     */
    @Transactional
    public EcomTrackingResponses.Proof recordProof(String orderNo, String kind, String receivedByName,
                                                   String note, BigDecimal lat, BigDecimal lng,
                                                   MultipartFile photo) {
        EcomOrder order = requireDriversOwnOrder(orderNo);

        if (!EcomOrder.ON_THE_WAY.equals(order.getEcomStatus())
                && !EcomOrder.DELIVERED.equals(order.getEcomStatus())) {
            throw new BusinessException(
                    "Order " + order.getOrderNo() + " has not been dispatched, so there is nothing to hand over yet.",
                    HttpStatus.CONFLICT, "ECOM_NOT_DISPATCHED");
        }

        String photoUrl = null;
        if (photo != null && !photo.isEmpty()) {
            photoUrl = images.store(photo, ImageStorageService.Bucket.DELIVERY_PROOF, order.getPidEcomOrder());
        }

        String k = kind == null ? "" : kind.trim().toUpperCase();
        if (photoUrl != null) k = EcomDeliveryProof.PHOTO;
        else if (receivedByName != null && !receivedByName.isBlank()) k = EcomDeliveryProof.NAME;
        else if (EcomDeliveryProof.PHOTO.equals(k)) {
            // Asked for a photograph and sent none: say so rather than writing a
            // row that claims a photograph exists.
            throw new BusinessException("No photograph was attached.", HttpStatus.BAD_REQUEST, "PHOTO_REQUIRED");
        } else k = EcomDeliveryProof.NOTE;

        if (EcomDeliveryProof.NOTE.equals(k) && (note == null || note.isBlank())) {
            throw new BusinessException(
                    "Say who took it, attach a photograph, or write what happened — one of the three.",
                    HttpStatus.BAD_REQUEST, "PROOF_EMPTY");
        }

        EcomDeliveryProof proof = proofs.findByPidEcomOrder(order.getPidEcomOrder())
                .orElseGet(() -> EcomDeliveryProof.builder()
                        .pidEcomOrder(order.getPidEcomOrder())
                        .pidBranch(order.getPidBranch())
                        .build());
        proof.setPidEmployee(SecurityUtils.getCurrentEmployeeId());
        proof.setProofKind(k);
        proof.setReceivedByName(trim(receivedByName, 150));
        if (photoUrl != null) proof.setPhotoUrl(photoUrl);
        proof.setNote(trim(note, 300));
        proof.setLatitude(lat);
        proof.setLongitude(lng);
        proof.setCapturedAt(LocalDateTime.now());
        proof.setCapturedBy(SecurityUtils.getCurrentUsername());
        proof.setStatus("Y");
        proofs.save(proof);

        audit.record("DELIVERY_PROOF", order.getPidEcomCustomer(), order.getPidBranch(),
                "ECOM_ORDER", order.getPidEcomOrder(),
                order.getOrderNo() + " · " + k + (proof.getReceivedByName() == null ? "" : " · " + proof.getReceivedByName()));

        log.info("Delivery proof for {} recorded by {} ({})", order.getOrderNo(), proof.getCapturedBy(), k);
        return view(proof, order);
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE CUSTOMER'S SIDE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * What the customer may know about where their order is.
     *
     * <p>The latest point only, and only while it is out. Before dispatch there
     * is nothing to say; after delivery the question is closed and the proof
     * takes its place.
     */
    @Transactional(readOnly = true)
    public EcomTrackingResponses.Tracking forCustomer(String orderNo) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomOrder order = orders.findByOrderNoAndPidEcomCustomer(orderNo, p.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderNo));

        boolean live = EcomOrder.ON_THE_WAY.equals(order.getEcomStatus());
        var builder = EcomTrackingResponses.Tracking.builder()
                .orderNo(order.getOrderNo())
                .status(order.getEcomStatus())
                .live(live);

        if (order.getPidTranMst() != null) {
            invoices.findById(order.getPidTranMst()).ifPresent(inv -> builder.deliveryManName(inv.getDeliveryManName()));
        }
        branches.findById(order.getPidBranch()).ifPresent(b -> builder
                .branchName(b.getBranchName()).branchPhone(b.getBranchPhoneNo()));

        proofs.findByPidEcomOrder(order.getPidEcomOrder())
                .ifPresent(pr -> builder.proof(view(pr, order)));

        if (live) {
            tracking.findFirstByPidEcomOrderOrderByRecordedAtDesc(order.getPidEcomOrder()).ifPresent(t -> {
                Double km = distanceToCustomer(order, t.getLatitude(), t.getLongitude());
                builder.latitude(t.getLatitude()).longitude(t.getLongitude())
                        .recordedAt(t.getRecordedAt())
                        .minutesAgo(minutesSince(t.getRecordedAt()))
                        .kmAway(km)
                        /* Distance, not minutes. Nothing here knows about roads,
                           traffic or one-way streets, and a time made up from a
                           straight line is a promise the pharmacy cannot keep. */
                        .note(km == null
                                ? "Your order is on its way."
                                : km < 0.3 ? "The driver is at your address."
                                : "About " + km + " km away, in a straight line.");
            });
        }
        return builder.build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       SCOPE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The order, if this person has any business touching it.
     *
     * <p>A driver may only report on their own round — the same rule the ERP
     * applies to advancing a stage — and anybody else must be staff at the
     * branch that is filling it. Another branch's order number is a 404, not a
     * 403: its existence is not their business either.
     */
    private EcomOrder requireDriversOwnOrder(String orderNo) {
        Long branch = branchScope.resolve(null);
        EcomOrder order = orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderNo));
        if (branch == null || !branch.equals(order.getPidBranch())) {
            throw new ResourceNotFoundException("Order", orderNo);
        }
        if (SecurityUtils.isDeliveryStaff()) {
            Long me = SecurityUtils.getCurrentEmployeeId();
            Long assigned = order.getPidTranMst() == null ? null
                    : invoices.findById(order.getPidTranMst()).map(PosTransactionMst::getDeliveryMan).orElse(null);
            if (me == null || !me.equals(assigned)) {
                throw new BusinessException(
                        "Order " + orderNo + " is not on your round.",
                        HttpStatus.FORBIDDEN, "NOT_YOUR_DELIVERY");
            }
        }
        return order;
    }

    /* ── small helpers ──────────────────────────────────────────────────── */

    private static Double distanceToCustomer(EcomOrder o, BigDecimal lat, BigDecimal lng) {
        if (o.getDeliveryLatitude() == null || o.getDeliveryLongitude() == null || lat == null || lng == null) return null;
        double km = EcomPharmacyService.haversineKm(
                lat.doubleValue(), lng.doubleValue(),
                o.getDeliveryLatitude().doubleValue(), o.getDeliveryLongitude().doubleValue());
        return Math.round(km * 10.0) / 10.0;
    }

    private static Long minutesSince(LocalDateTime when) {
        return when == null ? null : Duration.between(when, LocalDateTime.now()).toMinutes();
    }

    private EcomTrackingResponses.Proof view(EcomDeliveryProof p, EcomOrder o) {
        return EcomTrackingResponses.Proof.builder()
                .orderNo(o.getOrderNo())
                .kind(p.getProofKind())
                .receivedByName(p.getReceivedByName())
                .photoUrl(p.getPhotoUrl())
                .note(p.getNote())
                .latitude(p.getLatitude()).longitude(p.getLongitude())
                .capturedAt(p.getCapturedAt())
                .capturedBy(p.getCapturedBy())
                .build();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
