package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.request.SalesRequest;
import com.friendspharma.app.dto.response.EcomCartResponses;
import com.friendspharma.app.dto.response.TransactionResponse;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import com.friendspharma.app.service.impl.SalesServiceImpl;
import com.friendspharma.app.service.impl.NotificationServiceImpl;
import com.friendspharma.app.util.StockUnitConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkout (docs/ecommerce Phase 6) — where an online order becomes a real sale.
 *
 * <p>One transaction does all of it, and it either all happens or none of it
 * does: the cart is priced again from the shelf, the pharmacy-side customer row
 * is found or made, the ERP writes its invoice — FEFO allocation, batch-level
 * detail rows, stock movements and the ledger posting, through
 * {@link SalesServiceImpl#createSale(SalesRequest, SalesServiceImpl.SaleContext)},
 * the same method the till calls — and the customer-facing order is written
 * beside it with the prices as quoted.
 *
 * <p><b>Nothing about money comes from the client.</b> The request names an
 * address and a payment method. Quantities come from the cart, prices from the
 * ERP's own engine, and no discount percentage is ever forwarded: the two
 * fields {@code createSale} would honour from a caller — {@code invoiceDiscPct}
 * and a higher line {@code discountPct} — are deliberately left unset here.
 *
 * <p>The customer may be shown a price a moment before the shelf changes.
 * That is why the cart is re-priced inside this transaction and the order is
 * refused, with the line named, rather than quietly filled at a different
 * price or a smaller quantity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomCheckoutService {

    private final EcomCartService carts;
    private final EcomCartRepository cartRepo;
    private final EcomCartItemRepository cartItems;
    private final EcomCustomerRepository customers;
    private final EcomCustomerAddressRepository addresses;
    private final EcomOrderRepository orders;
    /** What the pharmacy charges to deliver, decided by its own policy. [Phase 9] */
    private final EcomDeliveryFeeService deliveryFees;
    /** What the customer's code is worth, valued again here. [Phase 12] */
    private final EcomCouponService coupons;
    /** What their points are worth, valued again here. [Phase 13] */
    private final EcomPointsService points;
    private final EcomOrderItemRepository orderItems;
    private final EcomOrderStatusHistoryRepository history;
    private final EcomCustomerBridgeService bridge;
    private final EcomPharmacyService pharmacies;
    private final EcomEligibilityService eligibility;
    private final AllBranchRepository branches;
    private final PosProductInfoRepository products;
    private final SalesServiceImpl sales;
    private final NotificationServiceImpl notifications;
    private final EcomAuditRecorder audit;
    private final EcomOrderNumberService orderNumbers;

    /**
     * Places one cart as one order at one pharmacy.
     *
     * @param cartId    the customer's own open cart
     * @param addressId which of their addresses it goes to
     * @param note      anything the pharmacy should know
     */
    @Transactional
    public EcomOrderView placeOrder(Long cartId, Long addressId, String note) {
        return placeOrder(cartId, addressId, note, null);
    }

    /**
     * @param redeemPoints how many loyalty points to spend on this order, or
     *                     null for none. What they are worth is decided here,
     *                     not sent. [Phase 13]
     */
    @Transactional
    public EcomOrderView placeOrder(Long cartId, Long addressId, String note, Integer redeemPoints) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomer customer = customers.findById(p.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", p.customerId()));
        if (!customer.isApproved()) {
            throw new BusinessException(
                    EcomCustomer.APPROVAL_PENDING.equals(customer.getApprovalStatus())
                            ? "Your account is waiting for approval. You can order once it is approved."
                            : "Your account cannot place orders. Contact the platform if you think this is a mistake.",
                    HttpStatus.FORBIDDEN, "ECOM_CUSTOMER_NOT_APPROVED");
        }

        EcomCart cart = carts.own(cartId);

        /* Eligibility again, on the branch the cart actually carries. The cart
           was checked when it was opened, but an approved region can be
           corrected and a pharmacy can stop trading wholesale between filling
           a basket and paying for it — and this is the last moment before real
           stock moves. */
        eligibility.requireShoppable(cart.getPidBranch());

        EcomCustomerAddress address = addresses.findByPidAddressAndPidEcomCustomerAndStatus(addressId, p.customerId(), "Y")
                .orElseThrow(() -> new ResourceNotFoundException("Address", addressId));

        /* Priced and stock-checked once more, here, inside the transaction that
           is about to take the stock. What the customer saw a minute ago is a
           quote; this is the answer. */
        EcomCartResponses.Cart priced = carts.price(cart, null, null);
        if (priced.getLines().isEmpty()) {
            throw new BusinessException("This cart is empty.", HttpStatus.CONFLICT, "ECOM_CART_EMPTY");
        }
        if (!priced.isReady()) {
            throw new BusinessException(
                    priced.getBlocker() != null ? priced.getBlocker() : "This cart cannot be ordered as it stands.",
                    HttpStatus.CONFLICT, "ECOM_CART_NOT_READY");
        }

        AllBranch branch = branches.findById(cart.getPidBranch())
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy", cart.getPidBranch()));
        pharmacies.get(branch.getPidBranch(), null, null);   // still online and trading, or 404

        // The pharmacy's own customer row for this person — made on the first order here.
        Long posCustomerId = bridge.ensureShadow(customer.getPidEcomCustomer(), branch.getPidBranch());

        /* What the journey costs, for THIS address rather than for wherever the
           customer happened to be browsing. The cart showed a quote against
           their default address; the order is charged for the one they chose,
           and the two differ exactly when those addresses do. [Phase 9] */
        var fee = deliveryFees.quote(branch, priced.getSubtotal(),
                address.getLatitude() == null ? null : address.getLatitude().doubleValue(),
                address.getLongitude() == null ? null : address.getLongitude().doubleValue());

        /* The coupon, valued again with its row held, inside the transaction
           that is about to spend it. The cart showed a quote; this is the
           answer, and a code that expired or ran out in between stops the order
           with its own sentence rather than quietly charging full price. */
        EcomCouponService.Quote coupon = null;
        if (cart.getCouponCode() != null) {
            coupon = coupons.quote(cart.getCouponCode(), customer.getPidEcomCustomer(),
                    customer.getCustomerType(), branch.getPidBranch(),
                    priced.getSubtotal(), fee.getCharge(), true);
            if (!coupon.isValid()) {
                throw new BusinessException(coupon.getMessage(), HttpStatus.CONFLICT, "ECOM_COUPON_NOT_APPLICABLE");
            }
        }
        BigDecimal couponOff = coupon != null ? coupon.getDiscount() : BigDecimal.ZERO;
        BigDecimal deliveryDue = coupon != null && coupon.isFreeDelivery() ? BigDecimal.ZERO : fee.getCharge();

        /* Points come after the coupon, on what is left of the goods — the
           order the brief fixes (docs/ecommerce 15 §2): tier price, standing
           discount, coupon, then points. Valued here rather than trusted from
           the request: the client says how many to spend, and the server says
           what that is worth and whether the balance covers it. */
        EcomPointsService.RedeemQuote pointsQuote = null;
        if (redeemPoints != null && redeemPoints > 0) {
            pointsQuote = points.redeemQuote(customer.getPidEcomCustomer(), branch.getPidBranch(),
                    customer.getCustomerType(), priced.getSubtotal().subtract(couponOff), redeemPoints);
            if (!pointsQuote.isValid()) {
                throw new BusinessException(pointsQuote.getMessage(), HttpStatus.CONFLICT, "ECOM_POINTS_NOT_USABLE");
            }
        }
        BigDecimal pointsOff = pointsQuote != null ? pointsQuote.getValue() : BigDecimal.ZERO;

        /* The request the till would have made. No prices, no line discounts, no
           delivery man, no "counter sale": the ERP prices every line itself
           from the same tier and rules the cart was quoted with — plus the
           delivery charge the pharmacy's policy worked out and the coupon as an
           exact invoice discount, both of which the ERP books to their own
           accounts rather than to revenue. */
        SalesRequest req = new SalesRequest();
        req.setCustomerId(posCustomerId);
        req.setTransactionDate(LocalDate.now());
        req.setPaymentType("Cash");
        req.setDeliveryAddress(oneLine(address));
        req.setRemarks(("ECOM " + priced.getLineCount() + " line(s)"));
        req.setDeliveryCharge(deliveryDue);
        /* One invoice discount, made of both: the ERP records what came off,
           and ECOM_ORDER records which part was the coupon and which the points. */
        BigDecimal discountOff = couponOff.add(pointsOff);
        if (discountOff.signum() > 0) req.setInvoiceDiscAmt(discountOff);
        req.setLines(priced.getLines().stream().map(l -> {
            SalesRequest.SalesLine line = new SalesRequest.SalesLine();
            line.setProductId(l.getProductId());
            line.setQuantity(l.getQty());
            line.setSalesUnit(l.getUnit());
            return line;
        }).toList());

        TransactionResponse invoice = sales.createSale(req, new SalesServiceImpl.SaleContext(
                branch.getPidBranch(), branch.getPidOrg(), branch.getPidCompany(),
                SalesServiceImpl.SaleContext.SOURCE_ECOM));

        /* What the ERP charged, which is what the customer pays. The cart's own
           subtotal is not used for the total: the invoice is the authority, and
           if the two ever disagree the invoice is what the pharmacy will
           deliver against. */
        BigDecimal invoiceTotal = invoice.getAmountTobePaid() != null ? invoice.getAmountTobePaid()
                : priced.getSubtotal();

        EcomOrder order = EcomOrder.builder()
                .orderNo(orderNumbers.next())
                .pidEcomCustomer(customer.getPidEcomCustomer())
                .pidBranch(branch.getPidBranch()).pidOrg(branch.getPidOrg())
                .pidTranMst(invoice.getId()).pidCustomer(posCustomerId)
                .ecomStatus(EcomOrder.NEW)
                .customerNameSnapshot(customer.getFullName())
                .phoneSnapshot(customer.getMobileNo())
                .customerTypeSnapshot(customer.getCustomerType())
                .deliveryLabel(address.getLabel()).deliveryRecipient(address.getRecipientName())
                .deliveryPhone(address.getPhone() != null ? address.getPhone() : customer.getMobileNo())
                .deliveryLine1(address.getLine1()).deliveryLine2(address.getLine2())
                .deliveryArea(address.getArea()).deliveryCity(address.getCity())
                .deliveryPostcode(address.getPostcode())
                .deliveryLatitude(address.getLatitude()).deliveryLongitude(address.getLongitude())
                .paymentMethod("COD")
                .subtotal(priced.getSubtotal())
                .discountAmount(priced.getSavings())
                .deliveryCharge(deliveryDue)
                .couponCode(coupon == null ? null : coupon.getCode())
                .couponAmount(couponOff)
                .pointsRedeemed(pointsQuote == null ? BigDecimal.ZERO : BigDecimal.valueOf(pointsQuote.getPoints()))
                .pointsValue(pointsOff)
                .grandTotal(invoiceTotal.setScale(2, RoundingMode.HALF_UP))
                .customerNote(note != null && !note.isBlank() ? note.trim() : cart.getNote())
                .placedAt(LocalDateTime.now())
                .build();
        order.setStatus("Y");
        order = orders.save(order);

        /* Written now, against a real order id, under the unique index that is
           the usage limit's actual enforcement. */
        coupons.spend(coupon, customer.getPidEcomCustomer(), order.getPidEcomOrder(), branch.getPidBranch());
        points.spend(pointsQuote, customer.getPidEcomCustomer(), order.getPidEcomOrder(),
                branch.getPidBranch(), order.getOrderNo());

        for (EcomCartResponses.Line l : priced.getLines()) {
            PosProductInfo pr = products.findById(l.getProductId()).orElse(null);
            EcomOrderItem item = EcomOrderItem.builder()
                    .pidEcomOrder(order.getPidEcomOrder())
                    .pidProduct(l.getProductId())
                    .pidGlobalProduct(pr == null ? null : pr.getPidGlobalProduct())
                    .productNameSnapshot(l.getName()).genericSnapshot(l.getGeneric()).companySnapshot(l.getCompany())
                    .salesUnit(l.getUnit()).qty(l.getQty())
                    .pcsPerUnitSnapshot(l.getPcsPerUnit()).qtyPcs(l.getQtyPcs())
                    .mrpSnapshot(l.getMrp()).unitPriceSnapshot(l.getUnitPrice())
                    .discountPctSnapshot(l.getDiscountPct()).lineTotal(l.getLineTotal())
                    .prescriptionFlagSnapshot(l.isPrescription() ? "Y" : "N")
                    .build();
            item.setStatus("Y");
            orderItems.save(item);
        }

        history.save(EcomOrderStatusHistory.builder()
                .pidEcomOrder(order.getPidEcomOrder()).fromStatus(null).toStatus(EcomOrder.NEW)
                .actorKind(EcomAuditLog.ACTOR_CUSTOMER).actorId(p.auditName())
                .reason("Order placed").build());

        // The cart has become the order; it is not reopened.
        cart.setCartStatus(EcomCart.CHECKED_OUT);
        cart.setCheckedOutAt(LocalDateTime.now());
        cart.setPidEcomOrder(order.getPidEcomOrder());
        cartRepo.save(cart);
        cartItems.deleteByCart(cart.getPidCart());

        tellTheBranch(order, branch, invoice);
        audit.record("ORDER_PLACED", customer.getPidEcomCustomer(), branch.getPidBranch(),
                "ECOM_ORDER", order.getPidEcomOrder(),
                order.getOrderNo() + " · invoice " + invoice.getInvoiceNo() + " · " + order.getGrandTotal());
        log.info("E-commerce order {} placed at branch {} as invoice {} ({})",
                order.getOrderNo(), branch.getPidBranch(), invoice.getInvoiceNo(), order.getGrandTotal());

        return new EcomOrderView(order, orderItems.findByPidEcomOrderOrderByPidOrderItem(order.getPidEcomOrder()),
                invoice.getInvoiceNo(), branch.getBranchName(), invoice.getStage());
    }

    /**
     * A new order waiting at the counter is worth knowing about. Failing to say
     * so must not undo the order, so it is best effort and loud in the log.
     */
    private void tellTheBranch(EcomOrder order, AllBranch branch, TransactionResponse invoice) {
        try {
            // The pharmacy's own notice board — the same one low stock and expiry use.
            notifications.broadcastTo(branch.getPidOrg(), branch.getPidBranch(), "ECOM_ORDER",
                    "New online order " + order.getOrderNo(),
                    order.getCustomerNameSnapshot() + " ordered " + order.getGrandTotal()
                    + " for delivery to " + shortAddress(order) + ". Invoice " + invoice.getInvoiceNo() + ".",
                    "INFO", "/activity");
        } catch (RuntimeException e) {
            log.error("Could not notify branch {} of order {}: {}", branch.getPidBranch(), order.getOrderNo(), e.getMessage());
        }
    }

    private static String shortAddress(EcomOrder o) {
        return String.join(", ", java.util.stream.Stream.of(o.getDeliveryArea(), o.getDeliveryCity())
                .filter(s -> s != null && !s.isBlank()).toList());
    }

    /** The address as one line, for {@code POS_TRANSACTION_MST.DELIVERY_ADDRESS} (200 chars). */
    static String oneLine(EcomCustomerAddress a) {
        List<String> parts = new ArrayList<>();
        for (String s : new String[]{ a.getLine1(), a.getLine2(), a.getArea(), a.getCity(), a.getPostcode() }) {
            if (s != null && !s.isBlank()) parts.add(s.trim());
        }
        String s = String.join(", ", parts);
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    /** An order with everything a screen needs, without a second round of queries. */
    public record EcomOrderView(EcomOrder order, List<EcomOrderItem> items,
                                String invoiceNo, String branchName, String erpStage) {}

    /** Units a customer may transact in — kept beside checkout so the two agree. */
    public static boolean isKnownUnit(String unit) {
        return StockUnitConverter.isKnownUnit(unit);
    }
}
