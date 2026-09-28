package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.request.EcomCartRequests;
import com.friendspharma.app.dto.response.EcomCartResponses;
import com.friendspharma.app.dto.response.EcomCatalogueResponses.Item;
import com.friendspharma.app.dto.response.EcomCatalogueResponses.UnitOffer;
import com.friendspharma.app.dto.response.EcomPharmacyResponses;
import com.friendspharma.app.entity.EcomCart;
import com.friendspharma.app.entity.EcomCartItem;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.EcomCustomerAddressRepository;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.EcomCartItemRepository;
import com.friendspharma.app.repository.EcomCartRepository;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import com.friendspharma.app.util.CustomerPricing;
import com.friendspharma.app.util.StockUnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * The cart (docs/ecommerce Phase 5).
 *
 * <p>One cart per pharmacy, because stock and price belong to the pharmacy
 * that will pack the order. A customer buying from three pharmacies has three
 * carts and three deliveries; nothing here pretends otherwise.
 *
 * <p><b>Nothing is priced at write time.</b> A line stores a product, a unit
 * and a quantity. Every read prices it again through
 * {@link EcomCatalogueService} — the till's own engine — and checks it against
 * the shelf, so a cart opened tomorrow shows tomorrow's price and tomorrow's
 * stock, and says so per line. Checkout (Phase 6) will do the same once more
 * inside the transaction that takes the stock.
 *
 * <p>An unapproved customer may fill a cart but not check out; the refusal
 * belongs to checkout, not to browsing, so nobody is stopped from planning an
 * order while the platform reviews their registration.
 */
@Service
@RequiredArgsConstructor
public class EcomCartService {

    /** Above this many lines a cart stops being a cart and starts being an order file. */
    private static final int MAX_LINES = 60;

    private final EcomCartRepository carts;
    private final EcomCartItemRepository items;
    private final EcomCustomerRepository customers;
    private final PosProductInfoRepository products;
    private final EcomCatalogueService catalogue;
    private final EcomPharmacyService pharmacies;
    private final EcomEligibilityService eligibility;
    private final EcomAuditRecorder audit;
    /* [Phase 9] What the journey costs, decided in one place for the cart, the
       checkout screen and the invoice, so all three agree. */
    private final EcomDeliveryFeeService deliveryFees;
    /** What a typed code is worth here — decided on the server, twice. [Phase 12] */
    private final EcomCouponService couponService;
    /** What their points could do here, and what this basket would earn. [Phase 13] */
    private final EcomPointsService points;
    private final AllBranchRepository branches;
    private final EcomCustomerAddressRepository addresses;

    /* ── reads ──────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public EcomCartResponses.Summary myCarts(Double lat, Double lng) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        List<EcomCartResponses.Cart> out = new ArrayList<>();
        for (EcomCart c : carts.findByPidEcomCustomerAndCartStatusOrderByUpdatedDateDesc(p.customerId(), EcomCart.OPEN)) {
            out.add(price(c, lat, lng));
        }
        return EcomCartResponses.Summary.builder()
                .carts(out).cartCount(out.size())
                .itemCount(out.stream().map(EcomCartResponses.Cart::getItemCount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .subtotal(out.stream().map(EcomCartResponses.Cart::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .build();
    }

    @Transactional(readOnly = true)
    public EcomCartResponses.Cart get(Long cartId, Double lat, Double lng) {
        return price(own(cartId), lat, lng);
    }

    /* ── writes ─────────────────────────────────────────────────────────── */

    @Transactional
    public EcomCartResponses.Cart add(EcomCartRequests.AddLine req, Double lat, Double lng) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        String unit = StockUnitConverter.normalise(req.getUnit());
        if (!StockUnitConverter.isKnownUnit(unit)) {
            throw new BusinessException("Unit must be BOX, STRIP or PCS.", HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_BAD_UNIT");
        }
        // The pharmacy must be open for business online; 404 otherwise.
        pharmacies.get(req.getBranchId(), null, null);

        PosProductInfo product = products.findById(req.getProductId())
                .filter(x -> "Y".equals(x.getStatus()) && x.getSupersededBy() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", req.getProductId()));
        // The product has to be this pharmacy's own row (or an org-wide row of its org).
        Item quote = catalogue.get(req.getProductId(), null, null);
        if (!Objects.equals(quote.getBranchId(), req.getBranchId())) {
            quote = catalogue.quoteAt(product, req.getBranchId());
        }
        UnitOffer offer = quote.getUnits().stream().filter(u -> u.getUnit().equals(unit)).findFirst().orElse(null);
        if (offer == null || !offer.isAllowed() || offer.getPrice() == null) {
            throw new BusinessException(
                    offer != null && offer.getReason() != null ? offer.getReason()
                            : "This pharmacy cannot sell " + quote.getName() + " by the " + unit.toLowerCase() + ".",
                    HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_UNIT_NOT_SELLABLE");
        }

        EcomCart cart = openCart(p.customerId(), req.getBranchId());
        EcomCartItem line = items.findByPidCartAndPidProductAndSalesUnit(cart.getPidCart(), req.getProductId(), unit).orElse(null);
        BigDecimal qty = req.getQty().setScale(2, RoundingMode.HALF_UP);
        if (line == null) {
            if (items.countByPidCart(cart.getPidCart()) >= MAX_LINES) {
                throw new BusinessException("A cart holds at most " + MAX_LINES + " lines. Check out what is in it first.",
                        HttpStatus.CONFLICT, "ECOM_CART_FULL");
            }
            line = EcomCartItem.builder().pidCart(cart.getPidCart()).pidProduct(req.getProductId()).salesUnit(unit).qty(qty).build();
            line.setStatus("Y");
        } else {
            line.setQty(Boolean.TRUE.equals(req.getReplace()) ? qty : line.getQty().add(qty));
        }
        items.save(line);
        carts.save(cart);   // touch, so "most recent cart" means something
        audit.record("CART_ADD", p.customerId(), cart.getPidBranch(), "PRODUCT", req.getProductId(),
                quote.getName() + " × " + qty + " " + unit);
        return price(cart, lat, lng);
    }

    @Transactional
    public EcomCartResponses.Cart setQty(Long cartId, Long lineId, BigDecimal qty, Double lat, Double lng) {
        EcomCart cart = own(cartId);
        EcomCartItem line = items.findByPidCartItemAndPidCart(lineId, cart.getPidCart())
                .orElseThrow(() -> new ResourceNotFoundException("Cart line", lineId));
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            items.delete(line);
        } else {
            line.setQty(qty.setScale(2, RoundingMode.HALF_UP));
            items.save(line);
        }
        carts.save(cart);
        return price(cart, lat, lng);
    }

    @Transactional
    public EcomCartResponses.Cart removeLine(Long cartId, Long lineId, Double lat, Double lng) {
        return setQty(cartId, lineId, BigDecimal.ZERO, lat, lng);
    }

    @Transactional
    public EcomCartResponses.Cart setNote(Long cartId, String note, Double lat, Double lng) {
        EcomCart cart = own(cartId);
        cart.setNote(note == null || note.isBlank() ? null : note.trim());
        return price(carts.save(cart), lat, lng);
    }

    /** Empties the cart and closes it. The customer decided against it; nothing is kept. */
    @Transactional
    public void abandon(Long cartId) {
        EcomCart cart = own(cartId);
        items.deleteByCart(cart.getPidCart());
        cart.setCartStatus(EcomCart.ABANDONED);
        carts.save(cart);
        audit.record("CART_ABANDONED", cart.getPidEcomCustomer(), cart.getPidBranch(), "CART", cart.getPidCart(), null);
    }

    /* ── pricing a cart ─────────────────────────────────────────────────── */

    /**
     * The cart as it stands now: every line re-priced and re-checked against
     * the shelf. Used by the cart screens and, in Phase 6, by checkout before
     * it commits.
     */
    @Transactional(readOnly = true)
    public EcomCartResponses.Cart price(EcomCart cart, Double lat, Double lng) {
        EcomPharmacyResponses.Pharmacy ph = pharmacies.get(cart.getPidBranch(), lat, lng);
        String tier = EcomCatalogueService.callerTier();

        List<EcomCartResponses.Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO, savings = BigDecimal.ZERO, count = BigDecimal.ZERO;
        boolean ready = true;

        for (EcomCartItem it : items.findByPidCartOrderByPidCartItem(cart.getPidCart())) {
            PosProductInfo product = products.findById(it.getPidProduct()).orElse(null);
            Item quote = null;
            if (product != null && "Y".equals(product.getStatus()) && product.getSupersededBy() == null) {
                quote = catalogue.quoteAt(product, cart.getPidBranch());
            }
            UnitOffer offer = quote == null ? null
                    : quote.getUnits().stream().filter(u -> u.getUnit().equals(it.getSalesUnit())).findFirst().orElse(null);

            String problem = null;
            BigDecimal lineTotal = null, lineSaving = null;
            Integer available = offer == null ? null : offer.getInStock();
            boolean lineOk = false;

            if (quote == null) {
                problem = "This pharmacy no longer lists this medicine.";
            } else if (offer == null || !offer.isAllowed() || offer.getPrice() == null) {
                problem = offer != null && offer.getReason() != null ? offer.getReason()
                        : "This pharmacy cannot sell it by the " + it.getSalesUnit().toLowerCase() + " any more.";
            } else if (available == null || BigDecimal.valueOf(available).compareTo(it.getQty()) < 0) {
                problem = available == null || available == 0
                        ? "Out of stock at this pharmacy."
                        : "Only " + available + " " + it.getSalesUnit().toLowerCase() + " left — reduce the quantity.";
            } else {
                lineOk = true;
            }
            if (offer != null && offer.getPrice() != null) {
                lineTotal = offer.getPrice().multiply(it.getQty()).setScale(2, RoundingMode.HALF_UP);
                if (offer.getMrp() != null) {
                    lineSaving = offer.getMrp().subtract(offer.getPrice()).max(BigDecimal.ZERO)
                            .multiply(it.getQty()).setScale(2, RoundingMode.HALF_UP);
                }
            }
            if (!lineOk) ready = false;
            /* The subtotal is what the cart would cost, including a line the shelf
               cannot fill yet: showing a cart of three boxes as costing nothing
               reads as a broken page, not as a warning. The warning is the line's
               own problem text, and it is what stops checkout. */
            if (lineTotal != null) {
                subtotal = subtotal.add(lineTotal);
                savings = savings.add(lineSaving == null ? BigDecimal.ZERO : lineSaving);
                count = count.add(it.getQty());
            }

            Integer per = offer == null ? null : offer.getPcsPerUnit();
            lines.add(EcomCartResponses.Line.builder()
                    .id(it.getPidCartItem()).productId(it.getPidProduct())
                    .name(quote != null ? quote.getName() : (product != null ? product.getProductName() : "Unknown medicine"))
                    .generic(quote == null ? null : quote.getGeneric()).company(quote == null ? null : quote.getCompany())
                    .imageUrl(quote == null ? null : quote.getImageUrl())
                    .prescription(quote != null && quote.isPrescription())
                    .unit(it.getSalesUnit()).qty(it.getQty()).pcsPerUnit(per)
                    .qtyPcs(per == null ? null : it.getQty().multiply(BigDecimal.valueOf(per)))
                    .mrp(offer == null ? null : offer.getMrp())
                    .listPrice(offer == null ? null : offer.getListPrice())
                    .discountPct(offer == null ? null : offer.getDiscountPct())
                    .unitPrice(offer == null ? null : offer.getPrice())
                    .lineTotal(lineTotal).lineSaving(lineSaving)
                    .available(available).inStock(lineOk).problem(problem)
                    .build());
        }

        /* One blocker, the one that matters most: an account that may not order at
           all comes before a cart that is merely too small. */
        EcomCustomer who = customers.findById(cart.getPidEcomCustomer()).orElse(null);
        String blocker = null;
        if (who != null && !who.isApproved()) {
            blocker = switch (who.getApprovalStatus()) {
                case EcomCustomer.APPROVAL_PENDING   -> "Your account is waiting for approval. You can order once it is approved.";
                case EcomCustomer.APPROVAL_SUSPENDED -> "Your account is suspended, so orders cannot be placed.";
                default -> "Your registration was not approved, so orders cannot be placed.";
            };
        } else if (lines.isEmpty()) {
            blocker = "This cart is empty.";
        } else if (!ready) {
            blocker = "Some lines need attention before you can check out.";
        } else if (ph.getMinOrderAmount() != null && subtotal.compareTo(ph.getMinOrderAmount()) < 0) {
            blocker = "This pharmacy delivers orders of " + ph.getMinOrderAmount().setScale(2, RoundingMode.HALF_UP)
                    + " or more. Add " + ph.getMinOrderAmount().subtract(subtotal).setScale(2, RoundingMode.HALF_UP) + " more.";
        }

        /* What delivery would cost this basket. Quoted against the address the
           order would most likely go to — their default — and only against the
           browsing point when they have no address yet, because the fee that
           matters is the one for where the medicine is going. */
        AllBranch branch = branches.findById(cart.getPidBranch()).orElse(null);
        double[] point = deliveryPoint(cart.getPidEcomCustomer(), lat, lng);
        var fee = deliveryFees.quote(branch, subtotal,
                point == null ? null : point[0], point == null ? null : point[1]);

        /* The code the customer typed, valued against this basket as it stands.
           A coupon that has since expired, run out, or stopped reaching the
           minimum says so on the cart rather than at the checkout. */
        EcomCouponService.Quote coupon = cart.getCouponCode() == null ? null
                : couponService.quote(cart.getCouponCode(), cart.getPidEcomCustomer(), tier,
                        cart.getPidBranch(), subtotal, fee.getCharge(), false);
        BigDecimal couponOff = coupon != null && coupon.isValid() ? coupon.getDiscount() : BigDecimal.ZERO;
        BigDecimal deliveryDue = coupon != null && coupon.isValid() && coupon.isFreeDelivery()
                ? BigDecimal.ZERO : fee.getCharge();

        /* Loyalty, as an offer rather than a decision: the cart says what the
           points could do, and the customer chooses at checkout. */
        BigDecimal goodsAfterCoupon = subtotal.subtract(couponOff).max(BigDecimal.ZERO);
        var redeem = points.redeemQuote(cart.getPidEcomCustomer(), cart.getPidBranch(), tier, goodsAfterCoupon, null);
        var earn = points.earnQuote(cart.getPidBranch(), tier, goodsAfterCoupon);

        return EcomCartResponses.Cart.builder()
                .id(cart.getPidCart()).branchId(ph.getId()).branchName(ph.getName()).branchAddress(ph.getAddress())
                .branchDistanceKm(ph.getDistanceKm()).deliversHere(ph.getDeliversHere())
                .minOrderAmount(ph.getMinOrderAmount()).openingHours(ph.getOpeningHours())
                .tier(tier).note(cart.getNote()).couponCode(cart.getCouponCode())
                .lines(lines).lineCount(lines.size()).itemCount(count)
                .subtotal(subtotal.setScale(2, RoundingMode.HALF_UP)).savings(savings.setScale(2, RoundingMode.HALF_UP))
                .deliveryCharge(deliveryDue)
                .deliveryNote(coupon != null && coupon.isValid() && coupon.isFreeDelivery()
                        ? "Free delivery with " + coupon.getCode() : fee.getNote())
                .deliveryFreeAbove(fee.getFreeAbove()).addForFreeDelivery(fee.getAddForFree())
                .couponDiscount(couponOff)
                .couponName(coupon == null ? null : coupon.getName())
                .couponMessage(coupon == null ? null : coupon.getMessage())
                .couponValid(coupon == null ? null : coupon.isValid())
                .pointsBalance(points.balanceOf(cart.getPidEcomCustomer()))
                .pointsUsable(redeem.isValid() ? redeem.getPoints() : 0)
                .pointsValue(redeem.isValid() ? redeem.getValue() : BigDecimal.ZERO)
                .pointsMessage(redeem.getMessage())
                .pointsToEarn(earn.getPoints())
                .total(subtotal.subtract(couponOff).add(deliveryDue).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))
                .ready(ready && !lines.isEmpty() && blocker == null).blocker(blocker)
                .build();
    }

    /**
     * Where this cart would be delivered, as far as anyone can tell right now.
     *
     * <p>A point given by the caller wins: the checkout screen sends the
     * coordinates of the address the customer has actually chosen, and the fee
     * it shows must be the fee that door will be charged. Failing that, their
     * default address, which is where an order usually goes. Failing that,
     * nothing — and a fee that needs a distance says so rather than inventing
     * a journey nobody measured.
     */
    private double[] deliveryPoint(Long customerId, Double lat, Double lng) {
        if (lat != null && lng != null) return new double[]{ lat, lng };
        return addresses.findByPidEcomCustomerAndStatusOrderByIsDefaultDescPidAddressAsc(customerId, "Y")
                .stream().filter(a -> a.getLatitude() != null && a.getLongitude() != null).findFirst()
                .map(a -> new double[]{ a.getLatitude().doubleValue(), a.getLongitude().doubleValue() })
                .orElse(null);
    }

    /**
     * Puts a coupon code on this cart, if it is worth anything here.
     *
     * <p>Stored only when it applies: a cart carrying a code that does nothing
     * would show a promise the checkout then takes away. The refusal explains
     * which rule it failed.
     */
    @Transactional
    public EcomCartResponses.Cart applyCoupon(Long cartId, String code, Double lat, Double lng) {
        EcomCart cart = own(cartId);
        EcomCartResponses.Cart priced = price(cart, lat, lng);

        var quote = couponService.quote(code, cart.getPidEcomCustomer(),
                EcomCatalogueService.callerTier(), cart.getPidBranch(),
                priced.getSubtotal(), priced.getDeliveryCharge(), false);
        if (!quote.isValid()) {
            throw new BusinessException(quote.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_COUPON_NOT_APPLICABLE");
        }

        cart.setCouponCode(quote.getCode());
        carts.save(cart);
        audit.record("CART_COUPON_APPLIED", cart.getPidEcomCustomer(), cart.getPidBranch(),
                "ECOM_CART", cart.getPidCart(), quote.getCode() + " · " + quote.getMessage());
        return price(cart, lat, lng);
    }

    /** Takes the code back off. Always allowed: it is the customer's own cart. */
    @Transactional
    public EcomCartResponses.Cart removeCoupon(Long cartId, Double lat, Double lng) {
        EcomCart cart = own(cartId);
        cart.setCouponCode(null);
        carts.save(cart);
        return price(cart, lat, lng);
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    /**
     * The customer's open cart at this pharmacy, opening one if there is none.
     *
     * <p>This is where a branch id first arrives from outside and starts
     * costing money, so it is where eligibility is checked: a retailer cannot
     * open a cart at a wholesale-only pharmacy, and a wholesaler cannot open
     * one outside their approved region however the id reached us
     * (docs/ecommerce 23). Checked again at checkout, because a region can be
     * corrected between filling a cart and paying for it.
     */
    @Transactional
    public EcomCart openCart(Long customerId, Long branchId) {
        eligibility.requireShoppable(branchId);
        return carts.findByPidEcomCustomerAndPidBranchAndCartStatus(customerId, branchId, EcomCart.OPEN)
                .orElseGet(() -> {
                    EcomCart c = EcomCart.builder().pidEcomCustomer(customerId).pidBranch(branchId).build();
                    c.setStatus("Y");
                    return carts.save(c);
                });
    }

    /** The caller's own cart, or 404. */
    @Transactional(readOnly = true)
    public EcomCart own(Long cartId) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        return carts.findByPidCartAndPidEcomCustomer(cartId, p.customerId())
                .filter(EcomCart::isOpen)
                .orElseThrow(() -> new ResourceNotFoundException("Cart", cartId));
    }

    /** Units this customer's tier may buy at all — what the storefront offers on a card. */
    public List<String> allowedUnits() {
        return CustomerPricing.allowedUnits(CustomerPricing.tierOf(EcomCatalogueService.callerTier()));
    }
}
