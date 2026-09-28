package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomBoardResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.PermissionGuard;
import com.friendspharma.app.service.impl.SalesServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The pharmacy's own board for the orders that came in online (MOD53).
 *
 * <p>An operational view, not a second sales engine. Everything that touches
 * stock, money or the delivery round is done by calling {@link SalesServiceImpl}
 * — the same methods Sales Activity calls — so a marketplace order is confirmed,
 * assigned, delivered and cancelled by exactly the code that does it for a sale
 * rung up at the counter. What lives here is the state the ERP has no column
 * for: where the customer is in the story they are being told.
 *
 * <p><b>Two state machines, kept in step.</b> {@code ECOM_STATUS} is the
 * customer-facing one and is a real machine: only the transitions in
 * {@link #ALLOWED} happen, and every one of them writes
 * {@code ECOM_ORDER_STATUS_HISTORY}. The ERP's stage list is untouched —
 * PREPARING and READY have no ERP equivalent and deliberately move nothing, so
 * packing an order changes what the customer sees without disturbing a single
 * report (docs/ecommerce 02 §3.2).
 *
 * <p><b>Branch is the scope.</b> Every read and every write starts from
 * {@link BranchScopeService#resolve}, and an order belonging to another
 * pharmacy is a 404 rather than a 403: a branch should not learn that another
 * branch's order number exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomOrderBoardService {

    /** The screen this service is behind, as the RBAC matrix knows it. */
    public static final String MOD_ORDERS = "MOD53";

    /** What the board may do to an order, and where it lands. */
    public static final String CONFIRM = "CONFIRM", PREPARE = "PREPARE", READY = "READY",
            DISPATCH = "DISPATCH", DELIVER = "DELIVER", CANCEL = "CANCEL";

    /** From this status, these actions. Anything else is refused. */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            EcomOrder.NEW,        List.of(CONFIRM, CANCEL),
            EcomOrder.CONFIRMED,  List.of(PREPARE, DISPATCH, CANCEL),
            EcomOrder.PREPARING,  List.of(READY, DISPATCH, CANCEL),
            EcomOrder.READY,      List.of(DISPATCH, CANCEL),
            EcomOrder.ON_THE_WAY, List.of(DELIVER),
            EcomOrder.DELIVERED,  List.of(),
            EcomOrder.CANCELLED,  List.of(),
            EcomOrder.RETURNED,   List.of());

    /** The one the board offers as the obvious next step. */
    private static final Map<String, String> NEXT = Map.of(
            EcomOrder.NEW,        CONFIRM,
            EcomOrder.CONFIRMED,  PREPARE,
            EcomOrder.PREPARING,  READY,
            EcomOrder.READY,      DISPATCH,
            EcomOrder.ON_THE_WAY, DELIVER);

    private static final Map<String, String> LABEL = Map.of(
            CONFIRM,  "Confirm",
            PREPARE,  "Start packing",
            READY,    "Mark ready",
            DISPATCH, "Dispatch",
            DELIVER,  "Delivered",
            CANCEL,   "Cancel");

    private static final List<String> BOARD_STATUSES = List.of(
            EcomOrder.NEW, EcomOrder.CONFIRMED, EcomOrder.PREPARING, EcomOrder.READY,
            EcomOrder.ON_THE_WAY, EcomOrder.DELIVERED, EcomOrder.CANCELLED, EcomOrder.RETURNED);

    private final EcomOrderRepository orders;
    private final EcomOrderItemRepository items;
    private final EcomOrderStatusHistoryRepository history;
    private final PosTransactionMstRepository invoices;
    private final PosTransactionDtlRepository invoiceLines;
    private final SalesServiceImpl sales;
    private final BranchScopeService branchScope;
    private final PermissionGuard permissions;
    private final EcomAuditRecorder audit;
    private final EcomCustomerRepository customers;
    private final AllRegionRepository regions;
    private final AllBranchRepository branches;
    /** A cancelled order gives its coupon back. [Phase 12] */
    private final EcomCouponService coupons;
    /** An arrived order pays its points; a cancelled one gives them back. [Phase 13] */
    private final EcomPointsService points;

    /* ══════════════════════════════════════════════════════════════════════
       READS
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Page<EcomBoardResponses.Row> list(Long branchId, String status, String q,
                                             LocalDate from, LocalDate to, int page, int size) {
        Long branch = requireBranch(branchId, PermissionGuard.Right.VIEW, "see online orders");
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));

        Page<EcomOrder> found = orders.searchForBranch(
                branch, blank(status), blank(q),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.atTime(23, 59, 59),
                pageable);

        /* One query for the invoices behind a page of orders, not one per row:
           the board shows 25 at a time and the stage, the driver and what is
           still owed all come off the same document. */
        Map<Long, PosTransactionMst> byId = invoicesFor(found.getContent());
        return found.map(o -> row(o, byId.get(o.getPidTranMst())));
    }

    @Transactional(readOnly = true)
    public Map<String, Long> counts(Long branchId) {
        Long branch = requireBranch(branchId, PermissionGuard.Right.VIEW, "see online orders");
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : BOARD_STATUSES) out.put(s, 0L);
        long open = 0, all = 0;
        for (Object[] r : orders.countByStatusForBranch(branch)) {
            String s = (String) r[0];
            long n = ((Number) r[1]).longValue();
            all += n;
            if (out.containsKey(s)) out.put(s, n);
            if (!EcomOrder.DELIVERED.equals(s) && !EcomOrder.CANCELLED.equals(s) && !EcomOrder.RETURNED.equals(s)) open += n;
        }
        // "Open" is what the board opens on: everything still needing work.
        out.put("OPEN", open);
        out.put("ALL", all);
        return out;
    }

    @Transactional(readOnly = true)
    public EcomBoardResponses.Detail detail(Long branchId, String orderNo) {
        Long branch = requireBranch(branchId, PermissionGuard.Right.VIEW, "see online orders");
        EcomOrder o = load(branch, orderNo);
        return detail(o);
    }

    /* ══════════════════════════════════════════════════════════════════════
       TRANSITIONS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Moves one order along, and moves the ERP document with it where the ERP
     * has somewhere to move.
     *
     * @param action  one of {@link #CONFIRM}, {@link #PREPARE}, {@link #READY},
     *                {@link #DISPATCH}, {@link #DELIVER}, {@link #CANCEL}
     * @param dmId    the delivery man, required by {@code DISPATCH}
     * @param reason  why, required by {@code CANCEL}
     */
    @Transactional
    public EcomBoardResponses.Detail act(Long branchId, String orderNo, String action,
                                         Long dmId, String reason) {
        String act = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);

        // Confirming and cancelling are decisions about the order; the rest is
        // the work of filling it. The matrix separates the two.
        PermissionGuard.Right right = (CONFIRM.equals(act) || CANCEL.equals(act))
                ? PermissionGuard.Right.APPROVE : PermissionGuard.Right.EDIT;
        Long branch = requireBranch(branchId, right, verb(act));

        EcomOrder o = load(branch, orderNo);
        String from = o.getEcomStatus();
        List<String> allowed = ALLOWED.getOrDefault(from, List.of());
        if (!allowed.contains(act)) {
            throw new BusinessException(
                    "Order " + o.getOrderNo() + " is " + human(from) + ", so it cannot be "
                    + past(act) + (allowed.isEmpty() ? ". There is nothing left to do to it."
                                                     : ". What you can do: " + labels(allowed) + "."),
                    HttpStatus.CONFLICT, "ECOM_TRANSITION_NOT_ALLOWED");
        }

        String to = switch (act) {
            case CONFIRM  -> EcomOrder.CONFIRMED;
            case PREPARE  -> EcomOrder.PREPARING;
            case READY    -> EcomOrder.READY;
            case DISPATCH -> EcomOrder.ON_THE_WAY;
            case DELIVER  -> EcomOrder.DELIVERED;
            case CANCEL   -> EcomOrder.CANCELLED;
            default -> throw new BusinessException("'" + action + "' is not something this board does.",
                    HttpStatus.BAD_REQUEST, "ECOM_UNKNOWN_ACTION");
        };

        // The ERP first, where it has a say. If it refuses — the goods are out,
        // the money is in, the driver is not a driver — the whole thing rolls
        // back and the customer is not told a story the branch cannot deliver.
        applyToInvoice(o, act, dmId, reason);

        o.setEcomStatus(to);
        if (EcomOrder.CONFIRMED.equals(to) && o.getConfirmedAt() == null) o.setConfirmedAt(LocalDateTime.now());
        if (EcomOrder.DELIVERED.equals(to)) o.setDeliveredAt(LocalDateTime.now());
        if (EcomOrder.DELIVERED.equals(to)) points.creditOnDelivery(o, o.getCustomerTypeSnapshot());
        if (EcomOrder.CANCELLED.equals(to)) {
            coupons.release(o.getPidEcomOrder(), "cancelled by the pharmacy");
            points.refundOnCancel(o.getPidEcomOrder(), o.getOrderNo());
            o.setCancelledAt(LocalDateTime.now());
            o.setCancelReason(reasonOr(reason, "Cancelled by the pharmacy"));
            o.setCancelledByKind(EcomAuditLog.ACTOR_STAFF);
        }
        orders.save(o);

        history.save(EcomOrderStatusHistory.builder()
                .pidEcomOrder(o.getPidEcomOrder()).fromStatus(from).toStatus(to)
                .actorKind(EcomAuditLog.ACTOR_STAFF).actorId(SecurityUtils.getCurrentUsername())
                .reason(blank(reason).isEmpty() ? null : reason.trim())
                .build());

        audit.record("ORDER_" + to, o.getPidEcomCustomer(), o.getPidBranch(),
                "ECOM_ORDER", o.getPidEcomOrder(),
                o.getOrderNo() + " · " + from + " → " + to
                + (blank(reason).isEmpty() ? "" : " · " + reason.trim()));

        log.info("E-commerce order {} {} → {} by {} (branch {})",
                o.getOrderNo(), from, to, SecurityUtils.getCurrentUsername(), branch);
        return detail(o);
    }

    /**
     * What the pharmacy's move means for the invoice underneath it.
     *
     * <p>PREPARING and READY are deliberately absent: they describe the shop
     * floor, not the document. Adding stages to the ERP for them would change
     * what every stage filter, chip and delivery report means (docs/ecommerce
     * 02 §3.2), to record something the ERP was never asked to know.
     */
    private void applyToInvoice(EcomOrder o, String act, Long dmId, String reason) {
        Long invoice = o.getPidTranMst();
        if (invoice == null) return;   // an order without a document has nothing to move

        switch (act) {
            case CONFIRM -> sales.advanceStage(invoice, "Confirmed", null);
            case DISPATCH -> {
                if (dmId == null) {
                    throw new BusinessException(
                            "Choose who is delivering order " + o.getOrderNo() + " before dispatching it.",
                            HttpStatus.BAD_REQUEST, "DELIVERY_MAN_REQUIRED");
                }
                // Assigned and then on the road: two stages, because that is
                // what the ERP records and what the delivery reports count.
                sales.advanceStage(invoice, "Delivery Assigned", dmId);
                sales.advanceStage(invoice, "On The Way", null);
            }
            case DELIVER -> sales.advanceStage(invoice, "Delivered", null);
            case CANCEL -> sales.cancelSale(invoice, reasonOr(reason, "Cancelled by the pharmacy"));
            default -> { /* PREPARE, READY: the ERP has no stage for these */ }
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       SCOPE
       ══════════════════════════════════════════════════════════════════════ */

    private Long requireBranch(Long branchId, PermissionGuard.Right right, String action) {
        Long branch = branchScope.resolve(branchId);
        if (branch == null) {
            throw new BusinessException(
                    "Your user account has no branch assigned, so there is no board to show.",
                    HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }
        if (!permissions.has(MOD_ORDERS, right)) {
            throw new BusinessException(
                    "You do not have permission to " + action + ". Ask somebody whose role holds "
                    + label(right) + " on E-Commerce Orders.",
                    HttpStatus.FORBIDDEN, "ECOM_NOT_PERMITTED");
        }
        return branch;
    }

    /** Another pharmacy's order number is a 404: its existence is not this branch's business. */
    private EcomOrder load(Long branch, String orderNo) {
        return orders.findByOrderNoAndPidBranch(orderNo, branch)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderNo));
    }

    /* ══════════════════════════════════════════════════════════════════════
       VIEWS
       ══════════════════════════════════════════════════════════════════════ */

    private Map<Long, PosTransactionMst> invoicesFor(List<EcomOrder> rows) {
        List<Long> ids = rows.stream().map(EcomOrder::getPidTranMst).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, PosTransactionMst> out = new HashMap<>();
        for (PosTransactionMst m : invoices.findAllById(ids)) out.put(m.getPidTranMst(), m);
        return out;
    }

    /**
     * The region a customer is approved to buy wholesale in.
     *
     * <p>Read from the customer record rather than snapshotted on the order:
     * unlike a price or a customer type, the region is not a fact about the
     * sale — it is where the account stands now, and that is what somebody
     * looking at a board wants to know.
     */
    private String regionOf(Long ecomCustomerId) {
        if (ecomCustomerId == null) return null;
        return customers.findById(ecomCustomerId)
                .map(EcomCustomer::getPidApprovedRegion)
                .flatMap(id -> id == null ? java.util.Optional.empty() : regions.findById(id))
                .map(AllRegion::getRegionName)
                .orElse(null);
    }

    private EcomBoardResponses.Row row(EcomOrder o, PosTransactionMst inv) {
        List<EcomOrderItem> lines = items.findByPidEcomOrderOrderByPidOrderItem(o.getPidEcomOrder());
        String next = NEXT.get(o.getEcomStatus());
        return EcomBoardResponses.Row.builder()
                .orderNo(o.getOrderNo()).status(o.getEcomStatus())
                .nextAction(next).nextLabel(next == null ? null : LABEL.get(next))
                .placedAt(o.getPlacedAt())
                .customerName(o.getCustomerNameSnapshot()).customerPhone(o.getPhoneSnapshot())
                .customerType(o.getCustomerTypeSnapshot())
                .customerRegion(regionOf(o.getPidEcomCustomer()))
                .lineCount(lines.size())
                .itemCount(lines.stream().map(EcomOrderItem::getQty).reduce(BigDecimal.ZERO, BigDecimal::add))
                .grandTotal(o.getGrandTotal()).paymentMethod(o.getPaymentMethod())
                .unpaid(inv == null || !"Paid".equalsIgnoreCase(inv.getPaymentStatus()))
                .invoiceId(o.getPidTranMst())
                .invoiceNo(inv == null ? null : inv.getInvoiceNo())
                .invoiceStage(inv == null ? null : inv.getStage())
                .deliveryManId(inv == null ? null : inv.getDeliveryMan())
                .deliveryManName(inv == null ? null : inv.getDeliveryManName())
                .deliveryArea(firstNonBlank(o.getDeliveryArea(), o.getDeliveryCity()))
                .prescription(lines.stream().anyMatch(i -> "Y".equals(i.getPrescriptionFlagSnapshot())))
                .build();
    }

    private EcomBoardResponses.Detail detail(EcomOrder o) {
        PosTransactionMst inv = o.getPidTranMst() == null ? null
                : invoices.findById(o.getPidTranMst()).orElse(null);

        /* The batches the invoice actually took, so whoever packs the parcel
           picks the same lots the document says were sold — and the expiry the
           customer will read off the strip. */
        Map<Long, List<EcomBoardResponses.Batch>> batches = new HashMap<>();
        if (inv != null) {
            for (PosTransactionDtl d : invoiceLines.findByPidTranMst(inv.getPidTranMst())) {
                batches.computeIfAbsent(d.getPidProduct(), k -> new ArrayList<>())
                        .add(EcomBoardResponses.Batch.builder()
                                .batchNo(d.getBatchNo())
                                .expiry(d.getExpiryDate() == null ? null : d.getExpiryDate().toString())
                                .qty(d.getQuantity()).unit(d.getSalesUnit())
                                .build());
            }
        }

        String next = NEXT.get(o.getEcomStatus());
        return EcomBoardResponses.Detail.builder()
                .orderNo(o.getOrderNo()).status(o.getEcomStatus())
                .nextAction(next).nextLabel(next == null ? null : LABEL.get(next))
                .allowedActions(ALLOWED.getOrDefault(o.getEcomStatus(), List.of()))
                .placedAt(o.getPlacedAt()).confirmedAt(o.getConfirmedAt())
                .deliveredAt(o.getDeliveredAt()).cancelledAt(o.getCancelledAt())
                .cancelReason(o.getCancelReason()).cancelledByKind(o.getCancelledByKind())
                .customerName(o.getCustomerNameSnapshot()).customerPhone(o.getPhoneSnapshot())
                .customerType(o.getCustomerTypeSnapshot()).posCustomerId(o.getPidCustomer())
                .customerRegion(regionOf(o.getPidEcomCustomer()))
                .fulfilBranchId(o.getPidBranch())
                .fulfilBranchName(branches.findById(o.getPidBranch()).map(AllBranch::getBranchName).orElse(null))
                .couponCode(o.getCouponCode()).couponAmount(o.getCouponAmount())
                .pointsRedeemed(o.getPointsRedeemed()).pointsValue(o.getPointsValue())
                .deliveryLabel(o.getDeliveryLabel()).deliveryRecipient(o.getDeliveryRecipient())
                .deliveryPhone(o.getDeliveryPhone()).deliveryAddress(address(o))
                .deliveryLatitude(o.getDeliveryLatitude()).deliveryLongitude(o.getDeliveryLongitude())
                .customerNote(o.getCustomerNote())
                .paymentMethod(o.getPaymentMethod()).subtotal(o.getSubtotal())
                .discountAmount(o.getDiscountAmount()).deliveryCharge(o.getDeliveryCharge())
                .grandTotal(o.getGrandTotal())
                .invoiceId(o.getPidTranMst())
                .invoiceNo(inv == null ? null : inv.getInvoiceNo())
                .invoiceStage(inv == null ? null : inv.getStage())
                .invoicePaymentStatus(inv == null ? null : inv.getPaymentStatus())
                .invoiceDue(inv == null ? null : due(inv))
                .deliveryManId(inv == null ? null : inv.getDeliveryMan())
                .deliveryManName(inv == null ? null : inv.getDeliveryManName())
                .lines(items.findByPidEcomOrderOrderByPidOrderItem(o.getPidEcomOrder()).stream()
                        .map(i -> EcomBoardResponses.Line.builder()
                                .productId(i.getPidProduct()).name(i.getProductNameSnapshot())
                                .generic(i.getGenericSnapshot()).company(i.getCompanySnapshot())
                                .unit(i.getSalesUnit()).qty(i.getQty()).pcsPerUnit(i.getPcsPerUnitSnapshot())
                                .unitPrice(i.getUnitPriceSnapshot()).lineTotal(i.getLineTotal())
                                .prescription("Y".equals(i.getPrescriptionFlagSnapshot()))
                                .batches(batches.getOrDefault(i.getPidProduct(), List.of()))
                                .build()).toList())
                .history(history.findByPidEcomOrderOrderByChangedAt(o.getPidEcomOrder()).stream()
                        .map(h -> EcomBoardResponses.Event.builder()
                                .status(h.getToStatus()).actorKind(h.getActorKind())
                                .by(EcomAuditLog.ACTOR_CUSTOMER.equals(h.getActorKind()) ? "The customer"
                                        : EcomAuditLog.ACTOR_STAFF.equals(h.getActorKind())
                                            ? (h.getActorId() == null ? "The pharmacy" : h.getActorId())
                                            : "System")
                                .reason(h.getReason()).at(h.getChangedAt()).build()).toList())
                .build();
    }

    /* ── small helpers ──────────────────────────────────────────────────── */

    private static BigDecimal due(PosTransactionMst m) {
        if ("N".equals(m.getStatus())) return BigDecimal.ZERO;   // a cancelled document owes nothing
        BigDecimal payable = m.getAmountTobePaid() == null ? BigDecimal.ZERO : m.getAmountTobePaid();
        BigDecimal given = m.getGivenAmount() == null ? BigDecimal.ZERO : m.getGivenAmount();
        return payable.subtract(given).max(BigDecimal.ZERO);
    }

    private static String address(EcomOrder o) {
        return String.join(", ", Arrays.stream(new String[]{
                        o.getDeliveryLine1(), o.getDeliveryLine2(), o.getDeliveryArea(),
                        o.getDeliveryCity(), o.getDeliveryPostcode()})
                .filter(s -> s != null && !s.isBlank()).toList());
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String blank(String s) { return s == null ? "" : s.trim(); }

    private static String reasonOr(String reason, String fallback) {
        return blank(reason).isEmpty() ? fallback : reason.trim();
    }

    private static String human(String status) {
        return switch (status) {
            case EcomOrder.NEW -> "waiting to be confirmed";
            case EcomOrder.CONFIRMED -> "confirmed";
            case EcomOrder.PREPARING -> "being packed";
            case EcomOrder.READY -> "packed and ready";
            case EcomOrder.ON_THE_WAY -> "on its way";
            case EcomOrder.DELIVERED -> "delivered";
            case EcomOrder.CANCELLED -> "cancelled";
            case EcomOrder.RETURNED -> "returned";
            default -> status;
        };
    }

    private static String past(String action) {
        return switch (action) {
            case CONFIRM -> "confirmed"; case PREPARE -> "put into packing";
            case READY -> "marked ready"; case DISPATCH -> "dispatched";
            case DELIVER -> "marked delivered"; case CANCEL -> "cancelled";
            default -> "changed";
        };
    }

    private static String verb(String action) {
        return switch (action) {
            case CONFIRM -> "confirm online orders"; case CANCEL -> "cancel online orders";
            default -> "work online orders";
        };
    }

    private static String labels(List<String> actions) {
        return String.join(", ", actions.stream().map(a -> LABEL.getOrDefault(a, a)).toList());
    }

    private static String label(PermissionGuard.Right r) {
        return switch (r) {
            case VIEW -> "View"; case CREATE -> "Create"; case EDIT -> "Edit"; case DELETE -> "Delete";
            case APPROVE -> "Approve"; case EXPORT -> "Export"; case PRINT -> "Print";
        };
    }
}
