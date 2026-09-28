package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.PosRequest;
import com.friendspharma.app.dto.response.PosResponse;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.interfaces.InventoryService;
import com.friendspharma.app.service.interfaces.PosService;
import com.friendspharma.app.service.interfaces.SalesDiscountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PosServiceImpl implements PosService {

    private final PosCartRepository           cartRepo;
    private final PosCartItemRepository       cartItemRepo;
    private final PosProductInfoRepository    productRepo;
    private final PosBoxInfoRepository        boxRepo;
    private final PosLeafInfoRepository       leafRepo;
    private final PosCustomerInfoRepository   customerRepo;
    private final PosTransactionMstRepository tranMstRepo;
    private final SalesDiscountService        discountService;
    private final InventoryService            inventoryService;

    // ─────────────────────────────────────────────────────────────
    // Barcode lookup
    // ─────────────────────────────────────────────────────────────

    /* readOnly transaction so the DTO can walk the product's lazy associations.
       Without it this method threw LazyInitializationException on every scan that
       actually found something — a miss returned a clean 404, a hit returned 500,
       which is why it reads as working until a barcode matches. */
    @Override
    @Transactional(readOnly = true)
    public PosResponse.BarcodeProductDto lookupByBarcode(String barcode, Long orgId) {
        /* [V72] Barcode first, then SKU as a fallback. SKU is no longer unique,
           so the fallback takes the first match by id rather than expecting one:
           a scan must put something on the counter, and failing the sale because
           two products share a shelf code would be the worse answer. Barcode is
           still unique, so the primary path is unambiguous. */
        PosProductInfo p = productRepo.findByBarcodeAndPidOrg(barcode, orgId)
                .orElseGet(() -> productRepo.findAllBySkuAndPidOrg(barcode, orgId)
                        .stream().findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Product with barcode/code '" + barcode + "' not found")));
        return toBarcodeDto(p);
    }

    // ─────────────────────────────────────────────────────────────
    // Cart lifecycle
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PosResponse.CartDto getOrCreateCart(Long orgId, Long branchId, Long userId) {
        PosCart cart = cartRepo.findByPidUserAndStage(userId, "OPEN")
                .orElseGet(() -> cartRepo.save(PosCart.builder()
                        .pidOrg(orgId).pidBranch(branchId).pidUser(userId)
                        .stage("OPEN")
                        .cartRef(generateRef())
                        .build()));
        return toCartDto(cart);
    }

    @Override
    @Transactional
    public PosResponse.CartDto addItem(Long cartId, PosRequest.AddItemRequest req) {
        PosCart cart = getOpenCart(cartId);

        PosProductInfo product = productRepo.findById(req.getPidProduct())
                .orElseThrow(() -> new ResourceNotFoundException("Product", req.getPidProduct()));

        BigDecimal unitPrice = resolveUnitPrice(product, req.getSalesUnit(), req.getUnitPriceOverride());
        BigDecimal discPct   = discountService.resolveEffectiveDiscount(
                cart.getPidOrg(), product.getPidProduct(),
                product.getPidCompany() != null ? product.getPidCompany() : 0L,
                req.getSalesUnit());
        BigDecimal lineTotal = computeLineTotal(unitPrice, req.getQty(), discPct);

        // Check if product already in cart — update qty instead of adding duplicate
        cart.getItems().stream()
                .filter(i -> i.getPidProduct().equals(req.getPidProduct())
                          && i.getSalesUnit().equals(req.getSalesUnit()))
                .findFirst()
                .ifPresentOrElse(existing -> {
                    BigDecimal newQty = existing.getQty().add(req.getQty());
                    existing.setQty(newQty);
                    existing.setLineTotal(computeLineTotal(unitPrice, newQty, discPct));
                }, () -> cart.getItems().add(PosCartItem.builder()
                        .cart(cart)
                        .pidProduct(product.getPidProduct())
                        .productName(product.getProductName())
                        .productImageUrl(product.getProductImageUrl())
                        .salesUnit(req.getSalesUnit())
                        .qty(req.getQty())
                        .unitPrice(unitPrice)
                        .discountPct(discPct)
                        .lineTotal(lineTotal)
                        .build()));

        recalcTotals(cart);
        return toCartDto(cartRepo.save(cart));
    }

    @Override
    @Transactional
    public PosResponse.CartDto updateItem(Long cartId, Long cartItemId, PosRequest.UpdateItemRequest req) {
        PosCart cart = getOpenCart(cartId);
        PosCartItem item = findItem(cart, cartItemId);

        if (req.getQty().compareTo(BigDecimal.ZERO) == 0) {
            cart.getItems().remove(item);
        } else {
            item.setQty(req.getQty());
            if (req.getDiscountPct() != null) item.setDiscountPct(req.getDiscountPct());
            item.setLineTotal(computeLineTotal(item.getUnitPrice(), item.getQty(), item.getDiscountPct()));
        }
        recalcTotals(cart);
        return toCartDto(cartRepo.save(cart));
    }

    @Override
    @Transactional
    public PosResponse.CartDto removeItem(Long cartId, Long cartItemId) {
        PosCart cart = getOpenCart(cartId);
        PosCartItem item = findItem(cart, cartItemId);
        cart.getItems().remove(item);
        recalcTotals(cart);
        return toCartDto(cartRepo.save(cart));
    }

    @Override
    @Transactional
    public PosResponse.CartDto clearCart(Long cartId) {
        PosCart cart = getOpenCart(cartId);
        cart.getItems().clear();
        recalcTotals(cart);
        return toCartDto(cartRepo.save(cart));
    }

    @Override
    @Transactional
    public PosResponse.CartDto holdCart(Long cartId, PosRequest.HoldCartRequest req) {
        PosCart cart = getOpenCart(cartId);
        if (cart.getItems().isEmpty()) throw new BusinessException("Cannot hold an empty cart");
        cart.setStage("HELD");
        cart.setHoldNote(req.getHoldNote());
        return toCartDto(cartRepo.save(cart));
    }

    @Override
    @Transactional
    public PosResponse.CartDto recallCart(PosRequest.RecallCartRequest req, Long userId) {
        PosCart held = cartRepo.findByCartRef(req.getCartRef())
                .orElseThrow(() -> new BusinessException("Cart '" + req.getCartRef() + "' not found"));
        if (!"HELD".equals(held.getStage())) {
            throw new BusinessException("Cart is not in HELD state");
        }
        // Close any current OPEN cart
        cartRepo.findByPidUserAndStage(userId, "OPEN").ifPresent(open -> {
            open.setStage("CANCELLED");
            cartRepo.save(open);
        });
        held.setStage("OPEN");
        held.setHoldNote(null);
        return toCartDto(cartRepo.save(held));
    }

    @Override
    public List<PosResponse.CartDto> listHeldCarts(Long branchId) {
        return cartRepo.findByPidBranchAndStageOrderByUpdatedDateDesc(branchId, "HELD")
                .stream().map(this::toCartDto).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public PosResponse.CartDto assignCustomer(Long cartId, Long customerId) {
        PosCart cart = getOpenCart(cartId);
        cart.setPidCustomer(customerId);
        return toCartDto(cartRepo.save(cart));
    }

    // ─────────────────────────────────────────────────────────────
    // Finalise / Quick Sale
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PosResponse.ReceiptDto finalise(PosRequest.FinaliseCartRequest req, Long userId) {
        PosCart cart = cartRepo.findById(req.getPidCart())
                .orElseThrow(() -> new ResourceNotFoundException("Cart", req.getPidCart()));

        if (!"OPEN".equals(cart.getStage()) && !"HELD".equals(cart.getStage())) {
            throw new BusinessException("Cart is already " + cart.getStage());
        }
        if (cart.getItems().isEmpty()) throw new BusinessException("Cart is empty");

        if (req.getPidCustomer() != null) cart.setPidCustomer(req.getPidCustomer());
        if (req.getAdditionalDiscountPct() != null) {
            cart.setAdditionalDiscountPct(req.getAdditionalDiscountPct());
            recalcTotals(cart);
        }

        // Delegate transaction creation to SalesService via InventoryService FEFO call
        Long tranId = inventoryService.createTransactionFromCart(cart, req.getPaymentType(),
                req.getRemarks(), userId);

        cart.setStage("FINALISED");
        cart.setFinalisedAt(LocalDateTime.now());
        cart.setPidTransaction(tranId);
        cartRepo.save(cart);

        return getReceipt(tranId);
    }

    @Override
    @Transactional
    public PosResponse.ReceiptDto quickSale(PosRequest.QuickSaleRequest req, Long userId) {
        // Build a transient cart and finalise immediately
        PosCart cart = PosCart.builder()
                .pidOrg(req.getPidOrg())
                .pidBranch(req.getPidBranch())
                .pidUser(userId)
                .pidCustomer(req.getPidCustomer())
                .stage("OPEN")
                .cartRef(generateRef())
                .additionalDiscountPct(
                        req.getAdditionalDiscountPct() != null
                                ? req.getAdditionalDiscountPct() : BigDecimal.ZERO)
                .build();
        cart.setItems(new ArrayList<>());

        for (PosRequest.AddItemRequest itemReq : req.getItems()) {
            PosProductInfo product = productRepo.findById(itemReq.getPidProduct())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemReq.getPidProduct()));
            BigDecimal unitPrice = resolveUnitPrice(product, itemReq.getSalesUnit(), itemReq.getUnitPriceOverride());
            BigDecimal discPct   = discountService.resolveEffectiveDiscount(
                    req.getPidOrg(), product.getPidProduct(),
                    product.getPidCompany() != null ? product.getPidCompany() : 0L,
                    itemReq.getSalesUnit());
            cart.getItems().add(PosCartItem.builder()
                    .cart(cart)
                    .pidProduct(product.getPidProduct())
                    .productName(product.getProductName())
                    .productImageUrl(product.getProductImageUrl())
                    .salesUnit(itemReq.getSalesUnit())
                    .qty(itemReq.getQty())
                    .unitPrice(unitPrice)
                    .discountPct(discPct)
                    .lineTotal(computeLineTotal(unitPrice, itemReq.getQty(), discPct))
                    .build());
        }
        recalcTotals(cart);
        cart = cartRepo.save(cart);

        Long tranId = inventoryService.createTransactionFromCart(cart, req.getPaymentType(),
                req.getRemarks(), userId);
        cart.setStage("FINALISED");
        cart.setFinalisedAt(LocalDateTime.now());
        cart.setPidTransaction(tranId);
        cartRepo.save(cart);

        return getReceipt(tranId);
    }

    @Override
    public PosResponse.ReceiptDto getReceipt(Long transactionId) {
        PosTransactionMst mst = tranMstRepo.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));
        return buildReceipt(mst);
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private PosCart getOpenCart(Long cartId) {
        PosCart cart = cartRepo.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart", cartId));
        if ("FINALISED".equals(cart.getStage()) || "CANCELLED".equals(cart.getStage())) {
            throw new BusinessException("Cart is already " + cart.getStage());
        }
        return cart;
    }

    private PosCartItem findItem(PosCart cart, Long itemId) {
        return cart.getItems().stream()
                .filter(i -> i.getPidCartItem().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", itemId));
    }

    private BigDecimal resolveUnitPrice(PosProductInfo p, String unit, BigDecimal override) {
        if (override != null && override.compareTo(BigDecimal.ZERO) > 0) return override;
        return switch (unit) {
            case "BOX"   -> p.getMrpBox()   != null ? p.getMrpBox()   : BigDecimal.ZERO;
            case "STRIP" -> p.getMrpStrip() != null ? p.getMrpStrip() : BigDecimal.ZERO;
            default      -> p.getMrpPcs()   != null ? p.getMrpPcs()   : BigDecimal.ZERO;
        };
    }

    private BigDecimal computeLineTotal(BigDecimal unitPrice, BigDecimal qty, BigDecimal discPct) {
        BigDecimal gross = unitPrice.multiply(qty);
        if (discPct == null || discPct.compareTo(BigDecimal.ZERO) == 0) return gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = gross.multiply(discPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return gross.subtract(discount).setScale(2, RoundingMode.HALF_UP);
    }

    private void recalcTotals(PosCart cart) {
        BigDecimal sub = cart.getItems().stream()
                .map(PosCartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        cart.setSubTotal(sub);

        BigDecimal addDisc = cart.getAdditionalDiscountPct() != null
                ? sub.multiply(cart.getAdditionalDiscountPct())
                       .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        cart.setDiscountAmount(addDisc);
        cart.setGrandTotal(sub.subtract(addDisc).setScale(2, RoundingMode.HALF_UP));
    }

    private String generateRef() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private PosResponse.BarcodeProductDto toBarcodeDto(PosProductInfo p) {
        return PosResponse.BarcodeProductDto.builder()
                .pidProduct(p.getPidProduct())
                .productName(p.getProductName())
                .productCode(p.getProductCode())
                .barcodeNo(p.getBarcodeNo())
                .productImageUrl(p.getProductImageUrl())
                .genericName(p.getGenericInfo() != null ? p.getGenericInfo().getGenericName() : null)
                .company(p.getCompanyInfo() != null ? p.getCompanyInfo().getCompanyName() : null)
                .category(p.getCategoryInfo() != null ? p.getCategoryInfo().getCategoryName() : null)
                .mrpBox(p.getMrpBox())
                .mrpStrip(p.getMrpStrip())
                .mrpPcs(p.getMrpPcs())
                // Derived from the product's Box → Leaf configuration. These
                // used to read SALES_PER_LEAF / SALES_PER, which are sales
                // percentages, so a 12% discount surfaced here as "12 pcs".
                .pcsPerStrip(pcsPerStrip(p))
                .stripPerBox(stripPerBox(p))
                .build();
    }

    /**
     * Pieces per strip for a product, from its Box 's leaf configuration.
     * Returns null when the product has no box/leaf set up, so the UI shows an
     * em-dash rather than a fabricated 1.
     */
    private Integer pcsPerStrip(PosProductInfo p) {
        if (p.getPidBox() == null) return null;
        return boxRepo.findById(p.getPidBox())
                .map(com.friendspharma.app.entity.PosBoxInfo::getPidLeaf)
                .flatMap(leafId -> leafId == null ? java.util.Optional.empty() : leafRepo.findById(leafId))
                .map(com.friendspharma.app.entity.PosLeafInfo::getNoOfPcs)
                .orElse(null);
    }

    /** Strips (leaves) per box for a product. */
    private Integer stripPerBox(PosProductInfo p) {
        if (p.getPidBox() == null) return null;
        return boxRepo.findById(p.getPidBox())
                .map(com.friendspharma.app.entity.PosBoxInfo::getNoOfLeaf)
                .orElse(null);
    }

    private PosResponse.CartDto toCartDto(PosCart c) {
        String customerName = null;
        if (c.getPidCustomer() != null) {
            customerName = customerRepo.findById(c.getPidCustomer())
                    .map(PosCustomerInfo::getCustomerName).orElse(null);
        }
        List<PosResponse.CartItemDto> itemDtos = c.getItems().stream().map(i ->
                PosResponse.CartItemDto.builder()
                        .pidCartItem(i.getPidCartItem())
                        .pidProduct(i.getPidProduct())
                        .productName(i.getProductName())
                        .productImageUrl(i.getProductImageUrl())
                        .salesUnit(i.getSalesUnit())
                        .qty(i.getQty())
                        .unitPrice(i.getUnitPrice())
                        .discountPct(i.getDiscountPct())
                        .lineTotal(i.getLineTotal())
                        .expiryDate(i.getExpiryDate())
                        .batchNo(i.getBatchNo())
                        .build()).collect(Collectors.toList());

        return PosResponse.CartDto.builder()
                .pidCart(c.getPidCart())
                .pidOrg(c.getPidOrg())
                .pidBranch(c.getPidBranch())
                .pidCustomer(c.getPidCustomer())
                .customerName(customerName)
                .stage(c.getStage())
                .holdNote(c.getHoldNote())
                .cartRef(c.getCartRef())
                .subTotal(c.getSubTotal())
                .discountAmount(c.getDiscountAmount())
                .additionalDiscountPct(c.getAdditionalDiscountPct())
                .grandTotal(c.getGrandTotal())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .items(itemDtos)
                .build();
    }

    private PosResponse.ReceiptDto buildReceipt(PosTransactionMst mst) {
        List<PosResponse.ReceiptLineDto> lines = mst.getDetails().stream().map(d ->
                PosResponse.ReceiptLineDto.builder()
                        .productName(null) // TODO: Join product to get name
                        .salesUnit(d.getSalesUnit())
                        .qty(d.getQuantity())
                        .unitPrice(d.getSalesPrice())
                        .discountPct(d.getDiscountPct())
                        .lineTotal(d.getTotalPrice())
                        .expiryDate(d.getExpiryDate())
                        .build()).collect(Collectors.toList());

        return PosResponse.ReceiptDto.builder()
                .invoiceNo(mst.getInvoiceNo())
                .invoiceDate(mst.getInvoiceDate())
                .customerName(mst.getCustomerName())
                .lines(lines)
                .subTotal(mst.getSubTotal())
                .discountAmount(mst.getDiscountAmount())
                .grandTotal(mst.getGrandTotal())
                .paymentType(mst.getPaymentType())
                .build();
    }
}
