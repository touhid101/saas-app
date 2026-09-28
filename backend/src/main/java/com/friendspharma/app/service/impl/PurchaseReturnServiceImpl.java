package com.friendspharma.app.service.impl;

import static com.friendspharma.app.util.DateRange.since;
import static com.friendspharma.app.util.DateRange.until;
import com.friendspharma.app.dto.request.PurchaseReturnRequest;
import com.friendspharma.app.dto.response.ReturnablePurchaseResponse;
import com.friendspharma.app.dto.response.TransactionResponse;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.entity.PosStockBatch;
import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.repository.PosStockBatchRepository;
import com.friendspharma.app.repository.PosSupplierInfoRepository;
import com.friendspharma.app.repository.PosTransactionDtlRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.StockPostingService;
import com.friendspharma.app.util.StockUnitConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.friendspharma.app.service.impl.accounting.AccountingHooks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Purchase returns — goods going back to the supplier.
 *
 * <p>The mirror of {@link SalesReturnServiceImpl}, with one difference that
 * shapes everything: this <em>removes</em> stock. A sales return can always be
 * accepted, because the goods are physically at the counter. Sending goods back
 * requires they still be on the shelf — so every line is bounded twice, by what
 * was bought and not yet returned, and by what the batch still holds.
 *
 * <p>Getting that second limit wrong is not a validation nicety. Without it a
 * storekeeper raises a debit note for stock that was dispensed last week, the
 * posting refuses at the last step, and the supplier has already been told the
 * goods are coming back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseReturnServiceImpl {

    private final PosTransactionMstRepository mstRepo;
    private final PosTransactionDtlRepository dtlRepo;
    private final PosSupplierInfoRepository supplierRepo;
    private final PosProductInfoRepository productRepo;
    private final PosStockBatchRepository batchRepo;
    private final StockPostingService stockPostingService;
    private final DocumentNumberService documentNumbers;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /** Only an approved purchase has actually put stock on the shelf. */
    private static final String STATUS_APPROVED = "A";

    /* ══════════════════════════════════════════════════════════════════════
       WHAT CAN GO BACK
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public ReturnablePurchaseResponse.Invoice returnableFor(Long purchaseId) {
        PosTransactionMst po = findPurchase(purchaseId);
        List<PosTransactionDtl> lines = dtlRepo.findByPidTranMst(purchaseId);

        Map<Long, PosProductInfo> products = products(lines);
        BigDecimal discPct = nvl(po.getInvoiceDisPct());

        List<ReturnablePurchaseResponse> rows = lines.stream()
                .map(d -> toRow(d, discPct, products, po.getPidBranch()))
                .toList();

        String blocked = blockedReason(po, rows);

        return ReturnablePurchaseResponse.Invoice.builder()
                .purchaseId(po.getPidTranMst())
                .invoiceNo(po.getInvoiceNo())
                .challanNo(po.getChallanNo())
                .transactionDate(po.getTransactionDate())
                .supplierId(po.getPidSupCust())
                .supplierName(supplierName(po.getPidSupCust()))
                .status(po.getStatus())
                .paymentType(po.getPaymentType())
                .invoiceDiscPct(discPct)
                .amountTobePaid(po.getAmountTobePaid())
                .returnable(blocked == null)
                .blockedReason(blocked)
                .priorReturns(priorReturns(purchaseId))
                .lines(rows)
                .build();
    }

    private ReturnablePurchaseResponse toRow(PosTransactionDtl d, BigDecimal discPct,
                                             Map<Long, PosProductInfo> products, Long branchId) {
        BigDecimal bought = nvl(d.getQuantity());
        BigDecimal done   = nvl(d.getReturnQty());
        BigDecimal onPaper = bought.subtract(done).max(BigDecimal.ZERO);

        // What the lot still holds, converted into this line's own unit so the
        // two limits are directly comparable.
        int pcsPerUnit = Math.max(d.getNoOfPcs() == null ? 1 : d.getNoOfPcs(), 1);
        PosStockBatch batch = d.getBatchNo() == null ? null
                : batchRepo.findByPidBranchAndPidProductAndBatchNo(
                        branchId, d.getPidProduct(), d.getBatchNo()).orElse(null);

        BigDecimal stockPcs = batch == null ? BigDecimal.ZERO : nvl(batch.getQtyPcs());
        // Whole units only: half a box cannot be sent back as a box.
        BigDecimal inStockUnits = stockPcs.divideToIntegralValue(BigDecimal.valueOf(pcsPerUnit));

        BigDecimal allowed = onPaper.min(inStockUnits);
        String unitWord = d.getSalesUnit() == null ? "unit" : d.getSalesUnit().toLowerCase();
        String limitedBy = null;
        if (inStockUnits.compareTo(onPaper) < 0) {
            if (stockPcs.compareTo(BigDecimal.ZERO) == 0) {
                limitedBy = "None of this batch is left in stock — it has all been sold or already returned.";
            } else if (inStockUnits.compareTo(BigDecimal.ZERO) == 0) {
                // The case that otherwise reads as a contradiction: the batch
                // shows stock, yet nothing can go back. It is a part unit, and
                // a supplier takes back whole ones.
                limitedBy = "Less than one whole " + unitWord + " is left — the remainder ("
                          + StockUnitConverter.describe(stockPcs, batch.getPcsPerBox(), batch.getPcsPerStrip())
                          + ") is a part " + unitWord + ", which cannot be sent back as one.";
            } else {
                limitedBy = "Limited by stock on hand: the rest of this line has been sold.";
            }
        }

        PosProductInfo p = products.get(d.getPidProduct());

        return ReturnablePurchaseResponse.builder()
                .lineId(d.getPidTranDtl())
                .productId(d.getPidProduct())
                .productName(p != null ? p.getProductName() : null)
                .sku(p != null ? p.getSku() : null)
                .batchNo(d.getBatchNo())
                .expiryDate(d.getExpiryDate())
                .purchaseUnit(d.getSalesUnit())
                .noOfPcs(d.getNoOfPcs())
                .purchasedQty(bought)
                .alreadyReturned(done)
                .notYetReturned(onPaper)
                .inStockQty(inStockUnits)
                .inStockDisplay(batch == null ? "—"
                        : StockUnitConverter.describe(stockPcs, batch.getPcsPerBox(), batch.getPcsPerStrip()))
                .returnableQty(allowed)
                .limitedBy(limitedBy)
                .costPerUnit(perUnit(d))
                .creditPerUnit(creditPerUnit(d, discPct))
                .expired(d.getExpiryDate() != null && d.getExpiryDate().isBefore(LocalDate.now()))
                .build();
    }

    private String blockedReason(PosTransactionMst po, List<ReturnablePurchaseResponse> rows) {
        if (!STATUS_APPROVED.equals(po.getStatus())) {
            return "Purchase " + po.getInvoiceNo() + " has not been approved, so nothing has been "
                 + "received into stock yet. Edit or delete the purchase instead of returning against it.";
        }
        boolean anyPaperLeft = rows.stream()
                .anyMatch(r -> r.getNotYetReturned().compareTo(BigDecimal.ZERO) > 0);
        if (!anyPaperLeft) {
            return "Every line on purchase " + po.getInvoiceNo() + " has already been returned in full.";
        }
        boolean anyStockLeft = rows.stream()
                .anyMatch(r -> r.getReturnableQty().compareTo(BigDecimal.ZERO) > 0);
        if (!anyStockLeft) {
            return "Nothing from purchase " + po.getInvoiceNo() + " is still in stock — it has all been "
                 + "sold. Goods that have left the shelf cannot be sent back to the supplier.";
        }
        return null;
    }

    private List<ReturnablePurchaseResponse.PriorReturn> priorReturns(Long purchaseId) {
        return mstRepo.findByPidTranMstReturnOrderByPidTranMstDesc(purchaseId).stream()
                .filter(r -> "PR".equals(r.getTransactionType()))
                .map(r -> ReturnablePurchaseResponse.PriorReturn.builder()
                        .returnId(r.getPidTranMst())
                        .invoiceNo(r.getInvoiceNo())
                        .transactionDate(r.getTransactionDate())
                        .amount(r.getAmountTobePaid())
                        .remarks(r.getRemarks())
                        .build())
                .toList();
    }

    /* ══════════════════════════════════════════════════════════════════════
       CREATE
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public TransactionResponse createReturn(PurchaseReturnRequest req) {
        PosTransactionMst po = findPurchase(req.getOriginalPurchaseId());

        if (!STATUS_APPROVED.equals(po.getStatus())) {
            throw new BusinessException(
                    "Purchase " + po.getInvoiceNo() + " has not been approved, so nothing has been "
                    + "received into stock. Edit the purchase instead of returning against it.",
                    HttpStatus.CONFLICT, "NOT_RECEIVED");
        }

        Long branchId = po.getPidBranch();
        if (branchId == null) {
            throw new BusinessException(
                    "Purchase " + po.getInvoiceNo() + " has no branch recorded, so there is no stock "
                    + "to take the goods out of.", HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }

        Map<Long, PosTransactionDtl> poLines = dtlRepo.findByPidTranMst(po.getPidTranMst())
                .stream().collect(Collectors.toMap(PosTransactionDtl::getPidTranDtl, d -> d));

        List<Requested> requested = validateLines(req, po, poLines, branchId);

        PosTransactionMst ret = new PosTransactionMst();
        ret.setPidOrg(po.getPidOrg());
        ret.setPidCompany(po.getPidCompany());
        ret.setPidBranch(branchId);
        ret.setPidSupCust(po.getPidSupCust());
        ret.setTransactionType("PR");
        ret.setInvoiceNo(nextInvoiceNo());
        ret.setTransactionDate(req.getTransactionDate() != null ? req.getTransactionDate() : LocalDate.now());
        ret.setPaymentType(req.getPaymentType() != null ? req.getPaymentType() : po.getPaymentType());
        // The header discount is already inside each line's credit; applying it
        // again here would take it off twice.
        ret.setInvoiceDisPct(BigDecimal.ZERO);
        ret.setRemarks(buildRemarks(req, po));
        // Approved on creation: a debit note that sits unapproved would leave the
        // goods counted as stock the pharmacy still owns while they are in a van
        // going back to the supplier.
        ret.setStatus(STATUS_APPROVED);
        ret.setFromSource("API");
        ret.setPidTranMstReturn(po.getPidTranMst());

        PosTransactionMst saved = mstRepo.save(ret);

        BigDecimal creditTotal = BigDecimal.ZERO;
        List<PosTransactionDtl> written = new ArrayList<>();

        for (Requested r : requested) {
            PosTransactionDtl src = r.poLine();

            PosTransactionDtl line = new PosTransactionDtl();
            line.setPidTranMst(saved.getPidTranMst());
            line.setPidProduct(src.getPidProduct());
            line.setQuantity(r.quantity());
            // Copied from the purchase: the goods leave the lot they landed in,
            // at the price they landed at.
            line.setBatchNo(src.getBatchNo());
            line.setExpiryDate(src.getExpiryDate());
            line.setSalesUnit(src.getSalesUnit());
            line.setSalesType(src.getSalesType());
            line.setUnitMode(src.getUnitMode());
            line.setNoOfPcs(src.getNoOfPcs());
            line.setMnpPrice(src.getMnpPrice());
            line.setMrpPrice(src.getMrpPrice());
            line.setSalesPrice(src.getSalesPrice());
            line.setDiscountPct(src.getDiscountPct());
            line.setTotalPrice(r.credit());
            line.setReturnQty(BigDecimal.ZERO);
            line.setPidTranDtlReturn(src.getPidTranDtl());

            written.add(dtlRepo.save(line));
            creditTotal = creditTotal.add(r.credit());

            src.setReturnQty(nvl(src.getReturnQty()).add(r.quantity()));
            dtlRepo.save(src);
        }

        saved.setTotalAmount(creditTotal);
        saved.setAmountTobePaid(creditTotal);
        saved.setInvoiceDisAmt(BigDecimal.ZERO);
        mstRepo.save(saved);

        // Stock last, once the document is whole. This is also the final guard:
        // issueFromBatch refuses if the lot cannot cover it, so a race between
        // the screen loading and the note being raised cannot oversend.
        stockPostingService.post(saved.getPidTranMst());
        accounting.purchaseReturnCreated(saved);

        log.info("Purchase return {} against {}: {} line(s), credit {}",
                saved.getInvoiceNo(), po.getInvoiceNo(), written.size(), creditTotal);

        return buildResponse(saved, written, supplierName(po.getPidSupCust()), po.getInvoiceNo());
    }

    private record Requested(PosTransactionDtl poLine, BigDecimal quantity, BigDecimal credit) {}

    private List<Requested> validateLines(PurchaseReturnRequest req, PosTransactionMst po,
                                          Map<Long, PosTransactionDtl> poLines, Long branchId) {
        BigDecimal discPct = nvl(po.getInvoiceDisPct());
        Map<Long, BigDecimal> perLine = new LinkedHashMap<>();

        // Summed before checking: two entries for the same line each pass alone
        // and together exceed what is available.
        for (PurchaseReturnRequest.Line l : req.getLines()) {
            perLine.merge(l.getOriginalLineId(), l.getQuantity(), BigDecimal::add);
        }

        List<Requested> out = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : perLine.entrySet()) {
            PosTransactionDtl src = poLines.get(e.getKey());
            if (src == null) {
                throw new ValidationException(
                        "Line " + e.getKey() + " is not part of purchase " + po.getInvoiceNo() + ".");
            }

            BigDecimal want   = e.getValue();
            BigDecimal bought = nvl(src.getQuantity());
            BigDecimal done   = nvl(src.getReturnQty());
            BigDecimal onPaper = bought.subtract(done);
            String product = productName(src.getPidProduct());
            String unit = src.getSalesUnit() == null ? "unit" : src.getSalesUnit().toLowerCase();

            if (want.compareTo(onPaper) > 0) {
                throw new ValidationException(done.compareTo(BigDecimal.ZERO) > 0
                        ? product + ": only " + strip(onPaper) + " of " + strip(bought) + " " + unit
                          + " can still be returned — " + strip(done) + " already went back."
                        : product + ": only " + strip(bought) + " " + unit + " were purchased.");
            }

            if (src.getBatchNo() == null || src.getBatchNo().isBlank()) {
                throw new BusinessException(
                        product + " was received without a batch number, so there is no lot to take "
                        + "it out of. Correct the purchase first.",
                        HttpStatus.CONFLICT, "NO_BATCH_ON_PURCHASE_LINE");
            }

            // The stock limit, checked here so the message names the shortfall
            // rather than surfacing as a bare posting failure.
            int pcsPerUnit = Math.max(src.getNoOfPcs() == null ? 1 : src.getNoOfPcs(), 1);
            PosStockBatch batch = batchRepo.findByPidBranchAndPidProductAndBatchNo(
                    branchId, src.getPidProduct(), src.getBatchNo()).orElse(null);
            BigDecimal stockPcs = batch == null ? BigDecimal.ZERO : nvl(batch.getQtyPcs());
            BigDecimal inStockUnits = stockPcs.divideToIntegralValue(BigDecimal.valueOf(pcsPerUnit));

            if (want.compareTo(inStockUnits) > 0) {
                throw new BusinessException(
                        product + ", batch " + src.getBatchNo() + ": only " + strip(inStockUnits)
                        + " " + unit + " of this lot are still in stock"
                        + (batch == null ? "" : " (" + StockUnitConverter.describe(
                                stockPcs, batch.getPcsPerBox(), batch.getPcsPerStrip()) + ")")
                        + ". Goods that have been sold cannot be sent back to the supplier.",
                        HttpStatus.CONFLICT, "INSUFFICIENT_STOCK_FOR_RETURN");
            }

            out.add(new Requested(src, want, creditFor(src, want, discPct)));
        }
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       MONEY
       ══════════════════════════════════════════════════════════════════════ */

    private static BigDecimal perUnit(PosTransactionDtl d) {
        BigDecimal qty = nvl(d.getQuantity());
        if (qty.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return nvl(d.getTotalPrice()).divide(qty, 6, RoundingMode.HALF_UP);
    }

    /**
     * What sending one unit back credits.
     *
     * <p>Net of the invoice discount, because that is what was actually paid.
     * Claiming the gross line price back would ask the supplier for money that
     * never changed hands — the mirror of over-refunding a customer, and just as
     * quietly wrong on every discounted invoice.
     */
    private static BigDecimal creditPerUnit(PosTransactionDtl d, BigDecimal discPct) {
        return netOfDiscount(perUnit(d), discPct).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal creditFor(PosTransactionDtl d, BigDecimal qty, BigDecimal discPct) {
        return netOfDiscount(perUnit(d).multiply(qty), discPct).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal netOfDiscount(BigDecimal amount, BigDecimal pct) {
        if (pct == null || pct.compareTo(BigDecimal.ZERO) <= 0) return amount;
        BigDecimal share = amount.multiply(pct).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return amount.subtract(share);
    }

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(String q, LocalDate from, LocalDate to, Pageable pageable) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        Page<PosTransactionMst> page = mstRepo.findPurchaseReturns(
                orgId, since(from), until(to), (q == null || q.isBlank()) ? "" : q, pageable);

        Map<Long, String> suppliers = supplierNames(page.getContent());
        Map<Long, String> originals = originalInvoiceNos(page.getContent());

        return page.map(m -> buildResponse(m, dtlRepo.findByPidTranMst(m.getPidTranMst()),
                lookup(suppliers, m.getPidSupCust()),
                lookup(originals, m.getPidTranMstReturn())));
    }

    /**
     * A map lookup that tolerates a missing key.
     *
     * <p><b>Why this is not just {@code map.get(key)}.</b> A return need not name
     * a purchase — six thousand of the ones in this database do not, because a
     * return can be raised against a supplier rather than against one invoice —
     * so the key is legitimately null. When no return on the page names a
     * purchase the helper above returns {@code Map.of()}, and an immutable map
     * throws NullPointerException on {@code get(null)} where a HashMap quietly
     * answers null. The page that was entirely unlinked returns — which is the
     * first page — therefore failed with a 500 while a mixed page worked.
     */
    private static <V> V lookup(Map<Long, V> map, Long key) {
        return key == null ? null : map.get(key);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long id) {
        PosTransactionMst m = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase return", id));
        if (!"PR".equals(m.getTransactionType())) {
            throw new ResourceNotFoundException("Purchase return", id);
        }
        requireSameOrg(m, "Purchase return");
        String original = m.getPidTranMstReturn() == null ? null
                : mstRepo.findById(m.getPidTranMstReturn()).map(PosTransactionMst::getInvoiceNo).orElse(null);
        return buildResponse(m, dtlRepo.findByPidTranMst(id), supplierName(m.getPidSupCust()), original);
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    private PosTransactionMst findPurchase(Long id) {
        PosTransactionMst m = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase", id));
        if (!"PU".equals(m.getTransactionType())) {
            throw new ResourceNotFoundException("Purchase", id);
        }
        requireSameOrg(m, "Purchase");
        return m;
    }

    private void requireSameOrg(PosTransactionMst m, String label) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && m.getPidOrg() != null && !orgId.equals(m.getPidOrg())) {
            throw new ResourceNotFoundException(label, m.getPidTranMst());
        }
    }

    /**
     * The same series every other document draws from.
     *
     * <p>Not a random suffix: INVOICE_NO is unique, so two notes raised in the
     * same month could collide and one save would fail for no reason anyone
     * could see. The shared counter also keeps returns in the audit order they
     * were actually raised in.
     */
    private String nextInvoiceNo() {
        return documentNumbers.nextInvoiceNo("PR");
    }

    private String buildRemarks(PurchaseReturnRequest req, PosTransactionMst po) {
        StringBuilder sb = new StringBuilder("Return against ").append(po.getInvoiceNo());
        if (req.getReason() != null && !req.getReason().isBlank()) sb.append(" — ").append(req.getReason().trim());
        if (req.getRemarks() != null && !req.getRemarks().isBlank()) sb.append(". ").append(req.getRemarks().trim());
        return sb.toString();
    }

    private TransactionResponse buildResponse(PosTransactionMst m, List<PosTransactionDtl> lines,
                                              String supplierName, String originalInvoiceNo) {
        Map<Long, PosProductInfo> products = products(lines);

        var lineResp = lines.stream().map(d -> TransactionResponse.TransactionLineResponse.builder()
                .lineId(d.getPidTranDtl())
                .productId(d.getPidProduct())
                .productName(products.containsKey(d.getPidProduct())
                        ? products.get(d.getPidProduct()).getProductName() : null)
                .batchNo(d.getBatchNo())
                .expiryDate(d.getExpiryDate())
                .quantity(d.getQuantity())
                .salesUnit(d.getSalesUnit())
                .mnpPrice(d.getMnpPrice())
                .mrpPrice(d.getMrpPrice())
                .discountPct(d.getDiscountPct())
                .totalPrice(d.getTotalPrice())
                .build()).toList();

        return TransactionResponse.builder()
                .id(m.getPidTranMst())
                .transactionType(m.getTransactionType())
                .invoiceNo(m.getInvoiceNo())
                .transactionDate(m.getTransactionDate())
                .paymentType(m.getPaymentType())
                .status(m.getStatus())
                .supplierId(m.getPidSupCust())
                .supplierName(supplierName)
                .branchId(m.getPidBranch())
                .subtotal(m.getTotalAmount())
                .amountTobePaid(m.getAmountTobePaid())
                // The purchase this reverses, so a debit note is never orphaned.
                .challanNo(originalInvoiceNo)
                .remarks(m.getRemarks())
                .lines(lineResp)
                .build();
    }

    private Map<Long, PosProductInfo> products(List<PosTransactionDtl> lines) {
        if (lines.isEmpty()) return Map.of();
        return productRepo.findAllById(lines.stream()
                        .map(PosTransactionDtl::getPidProduct).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(PosProductInfo::getPidProduct, p -> p, (a, b) -> a));
    }

    private Map<Long, String> supplierNames(List<PosTransactionMst> masters) {
        var ids = masters.stream().map(PosTransactionMst::getPidSupCust)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        // Same reason as originalInvoiceNos: a supplier with no name is a null
        // value, and Collectors.toMap will not have one.
        Map<Long, String> out = new HashMap<>();
        for (var s : supplierRepo.findAllById(ids)) {
            out.putIfAbsent(s.getPidSupplier(), s.getSupplierName());
        }
        return out;
    }

    private Map<Long, String> originalInvoiceNos(List<PosTransactionMst> returns) {
        var ids = returns.stream().map(PosTransactionMst::getPidTranMstReturn)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        /* Collected by hand rather than with Collectors.toMap, which throws on a
           null VALUE — and an invoice number is null on a purchase that never
           reached approval. One unnumbered purchase among the originals would
           have taken the whole page down with it. */
        Map<Long, String> out = new HashMap<>();
        for (PosTransactionMst m : mstRepo.findAllById(ids)) {
            out.putIfAbsent(m.getPidTranMst(), m.getInvoiceNo());
        }
        return out;
    }

    private String supplierName(Long id) {
        return id == null ? null
                : supplierRepo.findById(id).map(s -> s.getSupplierName()).orElse(null);
    }

    private String productName(Long id) {
        return productRepo.findById(id).map(PosProductInfo::getProductName).orElse("Product " + id);
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
