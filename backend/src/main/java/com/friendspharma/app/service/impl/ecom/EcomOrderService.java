package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomOrderResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * A customer's own orders: what they ordered, where it is, and calling one off.
 *
 * <p>Every read starts from the signed-in customer, so another customer's order
 * number is a 404. What is shown is the snapshot taken when the order was
 * placed — the prices they were charged, not today's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomOrderService {

    private final EcomOrderRepository orders;
    private final EcomOrderItemRepository items;
    private final EcomOrderStatusHistoryRepository history;
    private final PosTransactionMstRepository invoices;
    private final AllBranchRepository branches;
    private final EcomAuditRecorder audit;
    /** A cancelled order gives its coupon back. [Phase 12] */
    private final EcomCouponService coupons;
    /** And its points. [Phase 13] */
    private final EcomPointsService points;
    private final EcomPointLedgerRepository pointLedger;
    /** The ERP owns the invoice; a cancellation here is that service's cancellation. */
    private final com.friendspharma.app.service.impl.SalesServiceImpl sales;

    @Transactional(readOnly = true)
    public EcomOrderResponses.Page myOrders(int page, int size) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        int sz = Math.min(Math.max(size, 1), 50), pg = Math.max(page, 0);
        var found = orders.findByPidEcomCustomerOrderByPlacedAtDesc(p.customerId(), PageRequest.of(pg, sz));
        return EcomOrderResponses.Page.builder()
                .items(found.getContent().stream().map(o -> view(o, false)).toList())
                .page(pg).size(sz).total(found.getTotalElements()).totalPages(Math.max(1, found.getTotalPages()))
                .build();
    }

    @Transactional(readOnly = true)
    public EcomOrderResponses.Order get(String orderNo) {
        return view(own(orderNo), true);
    }

    /**
     * The customer calls an order off.
     *
     * <p>Only before the pharmacy has confirmed it. Afterwards the goods are
     * being packed, and what comes of that is the pharmacy's decision — a sales
     * return, or its own cancellation from Sales Activity — not something a
     * customer settles from a phone.
     *
     * <p>While it is still NEW the whole thing is undone: the pharmacy's
     * invoice is cancelled in the same transaction, which puts the pieces back
     * on the shelf and reverses the journals. Either both happen or neither
     * does.
     */
    @Transactional
    public EcomOrderResponses.Order cancel(String orderNo, String reason) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomOrder o = own(orderNo);
        if (!EcomOrder.NEW.equals(o.getEcomStatus())) {
            throw new BusinessException(
                    EcomOrder.CANCELLED.equals(o.getEcomStatus())
                            ? "This order was already cancelled."
                            : "The pharmacy has already started on this order. Call them on the number above to change it.",
                    HttpStatus.CONFLICT, "ECOM_ORDER_NOT_CANCELLABLE");
        }
        String from = o.getEcomStatus();
        String why = reason == null || reason.isBlank() ? "Cancelled by the customer" : reason.trim();

        /* The pharmacy's invoice goes with it. The order put stock on a van's
           list and money in the ledger; calling it off has to take both back,
           or the branch is left holding goods it cannot sell against an invoice
           nobody will pay. cancelSale reverses the stock posting and the
           journals in this same transaction and refuses — rolling all of this
           back — if the sale has moved beyond the point where that is honest
           (goods out, or money taken). [Phase 7] */
        if (o.getPidTranMst() != null) {
            sales.cancelSale(o.getPidTranMst(), why, p.auditName());
        }

        o.setEcomStatus(EcomOrder.CANCELLED);
        o.setCancelledAt(LocalDateTime.now());
        o.setCancelReason(why);
        o.setCancelledByKind(EcomAuditLog.ACTOR_CUSTOMER);
        orders.save(o);
        history.save(EcomOrderStatusHistory.builder()
                .pidEcomOrder(o.getPidEcomOrder()).fromStatus(from).toStatus(EcomOrder.CANCELLED)
                .actorKind(EcomAuditLog.ACTOR_CUSTOMER).actorId(p.auditName()).reason(o.getCancelReason())
                .build());
        coupons.release(o.getPidEcomOrder(), "order cancelled by the customer");
        points.refundOnCancel(o.getPidEcomOrder(), o.getOrderNo());
        audit.record("ORDER_CANCELLED", p.customerId(), o.getPidBranch(), "ECOM_ORDER", o.getPidEcomOrder(),
                o.getOrderNo() + " · " + o.getCancelReason());

        log.info("E-commerce order {} cancelled by the customer; invoice {} reversed for branch {}",
                o.getOrderNo(), o.getPidTranMst(), o.getPidBranch());
        return view(o, true);
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    EcomOrder own(String orderNo) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        return orders.findByOrderNoAndPidEcomCustomer(orderNo, p.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderNo));
    }

    EcomOrderResponses.Order view(EcomOrder o, boolean full) {
        AllBranch b = branches.findById(o.getPidBranch()).orElse(null);
        String invoiceNo = o.getPidTranMst() == null ? null
                : invoices.findById(o.getPidTranMst()).map(PosTransactionMst::getInvoiceNo).orElse(null);
        List<EcomOrderItem> lines = items.findByPidEcomOrderOrderByPidOrderItem(o.getPidEcomOrder());

        return EcomOrderResponses.Order.builder()
                .orderNo(o.getOrderNo()).status(o.getEcomStatus())
                .branchId(o.getPidBranch()).branchName(b == null ? null : b.getBranchName())
                .branchPhone(b == null ? null : b.getBranchPhoneNo())
                .invoiceNo(invoiceNo).customerType(o.getCustomerTypeSnapshot())
                .deliveryLabel(o.getDeliveryLabel()).deliveryRecipient(o.getDeliveryRecipient())
                .deliveryPhone(o.getDeliveryPhone()).deliveryAddress(address(o))
                .paymentMethod(o.getPaymentMethod())
                .subtotal(o.getSubtotal()).savings(o.getDiscountAmount())
                .deliveryCharge(o.getDeliveryCharge()).grandTotal(o.getGrandTotal())
                .couponCode(o.getCouponCode()).couponDiscount(o.getCouponAmount())
                .pointsRedeemed(o.getPointsRedeemed()).pointsValue(o.getPointsValue())
                .pointsEarned(pointLedger.findByPidEcomOrderAndEntryType(o.getPidEcomOrder(), EcomPointLedger.EARN)
                        .map(EcomPointLedger::getPoints).orElse(null))
                .note(o.getCustomerNote())
                .placedAt(o.getPlacedAt()).confirmedAt(o.getConfirmedAt())
                .deliveredAt(o.getDeliveredAt()).cancelledAt(o.getCancelledAt())
                .cancelReason(o.getCancelReason())
                .cancellable(EcomOrder.NEW.equals(o.getEcomStatus()))
                .lineCount(lines.size())
                .itemCount(lines.stream().map(EcomOrderItem::getQty).reduce(BigDecimal.ZERO, BigDecimal::add))
                .lines(lines.stream().map(EcomOrderService::line).toList())
                .history(!full ? List.of() : history.findByPidEcomOrderOrderByChangedAt(o.getPidEcomOrder()).stream()
                        .map(h -> EcomOrderResponses.Event.builder()
                                .status(h.getToStatus())
                                .by(EcomAuditLog.ACTOR_CUSTOMER.equals(h.getActorKind()) ? "You"
                                        : EcomAuditLog.ACTOR_STAFF.equals(h.getActorKind()) ? "The pharmacy" : "System")
                                .reason(h.getReason()).at(h.getChangedAt()).build()).toList())
                .build();
    }

    private static EcomOrderResponses.Line line(EcomOrderItem i) {
        return EcomOrderResponses.Line.builder()
                .productId(i.getPidProduct()).name(i.getProductNameSnapshot())
                .generic(i.getGenericSnapshot()).company(i.getCompanySnapshot())
                .unit(i.getSalesUnit()).qty(i.getQty()).pcsPerUnit(i.getPcsPerUnitSnapshot())
                .unitPrice(i.getUnitPriceSnapshot()).lineTotal(i.getLineTotal())
                .prescription("Y".equals(i.getPrescriptionFlagSnapshot()))
                .build();
    }

    private static String address(EcomOrder o) {
        return String.join(", ", Stream.of(o.getDeliveryLine1(), o.getDeliveryLine2(), o.getDeliveryArea(),
                        o.getDeliveryCity(), o.getDeliveryPostcode())
                .filter(s -> s != null && !s.isBlank()).toList());
    }
}
