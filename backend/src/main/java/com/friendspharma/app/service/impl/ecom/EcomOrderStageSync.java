package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.EcomAuditLog;
import com.friendspharma.app.entity.EcomOrder;
import com.friendspharma.app.entity.EcomOrderStatusHistory;
import com.friendspharma.app.event.SaleStageChanged;
import com.friendspharma.app.repository.EcomOrderRepository;
import com.friendspharma.app.repository.EcomOrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Keeps the customer's order in step with the pharmacy's invoice
 * (docs/ecommerce Phase 10).
 *
 * <p><b>Why this exists.</b> The E-Commerce Orders board moves both halves
 * together, but it is not the only way an invoice moves. A driver marks a
 * delivery done from the Delivery page; a manager cancels an invoice from Sales
 * Activity. Neither screen knows the sale began as an online order, and without
 * this the customer's screen would still say "on the way" about a parcel that
 * arrived an hour ago.
 *
 * <p><b>After the commit, in its own transaction.</b> The sale is the thing that
 * must not fail; a follow-up that keeps a second table tidy has no business
 * rolling one back. It also means that when the board itself made the move, the
 * ECOM order is <em>already</em> in the target status by the time this runs, so
 * it finds nothing to do and writes no second history row.
 *
 * <p>If this fails the invoice has still moved and the customer's screen lags.
 * That is logged loudly rather than hidden: the branch board reads the invoice
 * beside the order, so the disagreement is visible to somebody who can fix it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomOrderStageSync {

    /** ERP stage → what the customer is told. Stages not listed change nothing. */
    private static final Map<String, String> STATUS_OF = Map.of(
            "Confirmed",  EcomOrder.CONFIRMED,
            "On The Way", EcomOrder.ON_THE_WAY,
            "Delivered",  EcomOrder.DELIVERED,
            "Collected",  EcomOrder.DELIVERED,   // money taken at the door: it arrived
            "Cancelled",  EcomOrder.CANCELLED);

    /** Once an order is here, an earlier-sounding stage does not drag it back. */
    private static final Map<String, Integer> RANK = Map.of(
            EcomOrder.NEW, 0, EcomOrder.CONFIRMED, 1, EcomOrder.PREPARING, 2, EcomOrder.READY, 3,
            EcomOrder.ON_THE_WAY, 4, EcomOrder.DELIVERED, 5, EcomOrder.CANCELLED, 6, EcomOrder.RETURNED, 7);

    private final EcomOrderRepository orders;
    private final EcomOrderStatusHistoryRepository history;
    /** Read, never written, and only for the words somebody typed on the invoice. */
    private final com.friendspharma.app.repository.PosTransactionMstRepository invoices;
    /** A cancelled order gives its coupon back, however it was cancelled. [Phase 12] */
    private final EcomCouponService coupons;
    /** And pays or returns its points, however it arrived or was called off. [Phase 13] */
    private final EcomPointsService points;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSaleStageChanged(SaleStageChanged e) {
        String target = STATUS_OF.get(e.stage());
        if (target == null) return;

        orders.findByPidTranMst(e.tranMstId()).ifPresent(order -> {
            String from = order.getEcomStatus();
            if (target.equals(from)) return;                       // the board already did it

            /* Cancelled is final on this side. A cancelled sale that somebody
               then advances on the ERP must not tell a customer their called-off
               order is on its way. */
            if (EcomOrder.CANCELLED.equals(from) && !EcomOrder.CANCELLED.equals(target)) return;

            // And nothing goes backwards: a stage correction on the invoice is
            // not a reason to un-deliver an order the customer has received.
            if (RANK.getOrDefault(target, 0) < RANK.getOrDefault(from, 0)) return;

            order.setEcomStatus(target);
            if (EcomOrder.CONFIRMED.equals(target) && order.getConfirmedAt() == null) order.setConfirmedAt(LocalDateTime.now());
            if (EcomOrder.DELIVERED.equals(target) && order.getDeliveredAt() == null) order.setDeliveredAt(LocalDateTime.now());
            if (EcomOrder.DELIVERED.equals(target)) points.creditOnDelivery(order, order.getCustomerTypeSnapshot());
            if (EcomOrder.CANCELLED.equals(target)) {
                coupons.release(order.getPidEcomOrder(), "invoice cancelled in the ERP");
                points.refundOnCancel(order.getPidEcomOrder(), order.getOrderNo());
                if (order.getCancelledAt() == null) order.setCancelledAt(LocalDateTime.now());
                if (order.getCancelReason() == null) {
                    /* The reason somebody actually typed, not a house phrase.
                       "Shelf count was wrong" tells the customer something;
                       "Cancelled by the pharmacy" tells them it happened. */
                    order.setCancelReason(invoices.findById(e.tranMstId())
                            .map(m -> m.getCancelReason())
                            .filter(r -> r != null && !r.isBlank())
                            .orElse("Cancelled by the pharmacy"));
                }
                order.setCancelledByKind(EcomAuditLog.ACTOR_STAFF);
            }
            orders.save(order);

            history.save(EcomOrderStatusHistory.builder()
                    .pidEcomOrder(order.getPidEcomOrder())
                    .fromStatus(from).toStatus(target)
                    .actorKind(EcomAuditLog.ACTOR_STAFF)
                    .actorId(e.actor())
                    .reason("Followed the invoice (" + e.stage() + ")")
                    .build());

            log.info("Order {} followed invoice {} to {} ({} → {})",
                    order.getOrderNo(), e.tranMstId(), e.stage(), from, target);
        });
    }
}
