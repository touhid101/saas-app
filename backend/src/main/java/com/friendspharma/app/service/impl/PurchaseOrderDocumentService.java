package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.InvoiceDocumentResponse;
import com.friendspharma.app.dto.response.PurchaseOrderDocumentResponse;
import com.friendspharma.app.dto.response.PurchaseOrderDocumentResponse.Line;
import com.friendspharma.app.dto.response.PurchaseOrderDocumentResponse.Order;
import com.friendspharma.app.dto.response.PurchaseOrderDocumentResponse.Party;
import com.friendspharma.app.dto.response.PurchaseOrderDocumentResponse.Totals;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.entity.PosSupplierInfo;
import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AclUserRepository;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.repository.PosRequisitionMstRepository;
import com.friendspharma.app.repository.PosSupplierInfoRepository;
import com.friendspharma.app.repository.PosTransactionDtlRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.util.AmountInWords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A purchase order, assembled for printing and sending to the supplier.
 *
 * <p><b>Why this exists.</b> Splitting a requisition produces one draft order
 * per supplier, and each supplier needs its own copy — the part addressed to it
 * and nothing else. Sending the whole requisition would show one supplier what
 * is being bought from its competitors, and the prices being paid for it.
 *
 * <p>Built exactly like {@link InvoiceDocumentService}: same letterhead read
 * from the branch, same tenancy wall, every figure computed here rather than in
 * a browser. The two documents leave the same pharmacy and should read as
 * though they did.
 *
 * <p>The requisition number is carried on the order because that is what the
 * buyer and the supplier will quote at each other afterwards. It is looked up
 * through the requisition lines that point at this order, not parsed back out
 * of the remarks text the split happened to write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderDocumentService {

    private final PosTransactionMstRepository mstRepo;
    private final PosTransactionDtlRepository dtlRepo;
    private final PosSupplierInfoRepository   supplierRepo;
    private final PosProductInfoRepository    productRepo;
    private final AllBranchRepository         branchRepo;
    private final AclUserRepository           userRepo;
    private final PosRequisitionMstRepository requisitionRepo;
    private final BranchScopeService          branchScope;

    @Transactional(readOnly = true)
    public PurchaseOrderDocumentResponse forPurchase(Long purchaseId) {
        PosTransactionMst m = mstRepo.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order", purchaseId));

        // The tenancy wall, as on the invoice: resolve() throws for anyone who
        // may not read this branch, so an order id belonging to another pharmacy
        // cannot be printed by guessing the number.
        Long allowed = branchScope.resolve(m.getPidBranch());
        if (!allowed.equals(m.getPidBranch())) {
            throw new ResourceNotFoundException("Purchase order", purchaseId);
        }

        List<PosTransactionDtl> details = dtlRepo.findByPidTranMst(purchaseId);
        List<Line> lines = linesOf(details);

        return PurchaseOrderDocumentResponse.builder()
                .branch(branchOf(m))
                .order(orderOf(m))
                .supplier(supplierOf(m))
                .lines(lines)
                .totals(totalsOf(m, lines))
                .amountInWords(AmountInWords.format(m.getAmountTobePaid()))
                .build();
    }

    /* ── Letterhead ──────────────────────────────────────────────────────── */

    /** The branch placing the order, exactly as the invoice prints it. */
    private InvoiceDocumentResponse.Branch branchOf(PosTransactionMst m) {
        AllBranch b = m.getPidBranch() == null ? null
                : branchRepo.findById(m.getPidBranch()).orElse(null);
        if (b == null) return InvoiceDocumentResponse.Branch.builder().build();

        String trading = blankToNull(b.getReportHeader());
        return InvoiceDocumentResponse.Branch.builder()
                .name(b.getBranchName())
                .tradingName(trading != null ? trading : b.getBranchName())
                .address(blankToNull(b.getBranchAddress()))
                .phone(blankToNull(b.getBranchPhoneNo()))
                .email(blankToNull(b.getBranchEmail()))
                .logoUrl(blankToNull(b.getLogoUrl()))
                .build();
    }

    /* ── The order ───────────────────────────────────────────────────────── */

    private Order orderOf(PosTransactionMst m) {
        String createdBy = m.getInsertedBy();
        String displayName = createdBy == null ? null
                : userRepo.findByUserNameIgnoreCase(createdBy)
                          .map(u -> blankToNull(u.getFullName()) != null ? u.getFullName() : createdBy)
                          .orElse(createdBy);

        var req = requisitionRepo.findByPurchaseOrder(m.getPidTranMst()).stream().findFirst();

        return Order.builder()
                .id(m.getPidTranMst())
                .orderNo(m.getInvoiceNo())
                .date(m.getTransactionDate())
                .requisitionNo(req.map(r -> r.getRequisitionNo()).orElse(null))
                .requisitionDate(req.map(r -> r.getReqDate()).orElse(null))
                .status(statusLabel(m.getStatus()))
                .paymentType(m.getPaymentType())
                .purchaseType(m.getPurchaseType())
                .remarks(blankToNull(m.getRemarks()))
                .createdBy(displayName)
                .printedAt(LocalDateTime.now())
                .build();
    }

    /* ── Who it is addressed to ──────────────────────────────────────────── */

    private Party supplierOf(PosTransactionMst m) {
        PosSupplierInfo s = m.getPidSupCust() == null ? null
                : supplierRepo.findById(m.getPidSupCust()).orElse(null);
        if (s == null) return Party.builder().name("—").build();

        // City is part of the address on paper, not a separate field a reader
        // has to assemble.
        String address = blankToNull(s.getAddress());
        String city = blankToNull(s.getCity());
        String full = address == null ? city
                    : city == null ? address
                    : address + ", " + city;

        return Party.builder()
                .name(s.getSupplierName())
                .contactPerson(blankToNull(s.getResponsiblePerson()))
                .mobile(blankToNull(s.getMobileNo()))
                .phone(blankToNull(s.getRespPhoneNo()))
                .email(blankToNull(s.getEmailAddress()))
                .address(full)
                .build();
    }

    /* ── Lines ───────────────────────────────────────────────────────────── */

    private List<Line> linesOf(List<PosTransactionDtl> details) {
        // One query for every product on the order rather than one per line.
        Map<Long, PosProductInfo> products = details.isEmpty() ? Map.of()
                : productRepo.findAllById(details.stream().map(PosTransactionDtl::getPidProduct).toList())
                        .stream()
                        .collect(Collectors.toMap(PosProductInfo::getPidProduct, Function.identity(),
                                                  (a, b) -> a));

        List<Line> lines = new ArrayList<>(details.size());
        int sl = 1;
        for (PosTransactionDtl d : details) {
            PosProductInfo p = products.get(d.getPidProduct());
            BigDecimal qty = nvl(d.getQuantity());
            Integer per = d.getNoOfPcs();

            lines.add(Line.builder()
                    .sl(sl++)
                    .product(p == null ? ("Product " + d.getPidProduct()) : p.getProductName())
                    .unit(blankToNull(d.getSalesType()) != null ? d.getSalesType() : d.getSalesUnit())
                    .quantity(qty)
                    .piecesPerUnit(per)
                    // Stated rather than left to the reader: a supplier checking
                    // "12 BOX" against its own pack size needs the piece count.
                    .quantityPcs(per == null ? null : qty.multiply(BigDecimal.valueOf(per)))
                    .unitCost(nvl(d.getMnpPrice()))
                    .mrp(nvl(d.getMrpPrice()))
                    .discountAmount(nvl(d.getFlatDiscount()))
                    .totalPrice(nvl(d.getTotalPrice()))
                    .build());
        }
        return lines;
    }

    /* ── Totals ──────────────────────────────────────────────────────────── */

    private Totals totalsOf(PosTransactionMst m, List<Line> lines) {
        BigDecimal qty = lines.stream()
                .map(Line::getQuantity)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(nvl(b)));

        return Totals.builder()
                .subtotal(nvl(m.getTotalAmount()))
                .discount(nvl(m.getTotalDiscount()))
                .tax(nvl(m.getTotalTax()))
                .serviceCharge(nvl(m.getServiceCharge()))
                .grandTotal(nvl(m.getAmountTobePaid()))
                .lineCount(lines.size())
                .totalQuantity(qty)
                .build();
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    /**
     * The stored status as something a supplier can read.
     *
     * <p>The column holds single letters — D, P, A — which are the application's
     * business and not the supplier's. Printing the letter put a bare "A" on a
     * document going out of the building.
     *
     * <p>The words are chosen from the supplier's side of the transaction: an
     * approved purchase means the goods arrived and were taken into stock, which
     * on their copy reads as received, not as "approved".
     */
    private static String statusLabel(String code) {
        if (code == null) return null;
        return switch (code.trim().toUpperCase()) {
            case "D" -> "Order — not yet received";
            case "P" -> "Awaiting approval";
            case "A" -> "Received";
            default  -> code;
        };
    }

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
