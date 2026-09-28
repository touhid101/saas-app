package com.friendspharma.app.service.impl;

import static com.friendspharma.app.util.DateRange.since;
import static com.friendspharma.app.util.DateRange.until;
import com.friendspharma.app.dto.request.SalesReturnRequest;
import com.friendspharma.app.dto.response.ReturnableLineResponse;
import com.friendspharma.app.dto.response.TransactionResponse;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.PosCustomerInfoRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.repository.PosTransactionDtlRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.StockPostingService;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Sales returns — goods coming back, against the invoice that sent them out.
 *
 * <p>SRS FR-11 requires the link to the original transaction. That link is the
 * mechanism, not the paperwork: the sale line records the batch the goods left
 * from, the expiry on that batch, and the price they were sold at. A return that
 * did not read them would have to guess, and guessing the batch puts stock back
 * at the wrong cost — quietly wrong, in a way that only shows up later as a
 * margin nobody can explain.
 *
 * <p>The stock side already existed: {@link StockPostingService} posts an
 * {@code SR} line inbound to the batch it names. What was missing was everything
 * that decides <em>what</em> to post.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesReturnServiceImpl {

    private final PosTransactionMstRepository mstRepo;
    private final PosTransactionDtlRepository dtlRepo;
    private final PosCustomerInfoRepository customerRepo;
    private final PosProductInfoRepository productRepo;
    private final StockPostingService stockPostingService;
    private final DocumentNumberService documentNumbers;
    private final BranchScopeService branchScope;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /**
     * The stages at which goods have actually left the building.
     *
     * <p>Before Confirmed the invoice is still a draft and nothing has moved —
     * the correction there is an edit, not a return. Raising a return against a
     * draft would put stock in that the sale never took out.
     */
    private static final List<String> RETURNABLE_STAGES = List.of(
            "Confirmed", "Delivery Assigned", "On The Way", "Delivered", "Collected");

    /* ══════════════════════════════════════════════════════════════════════
       WHAT CAN COME BACK
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The invoice as a return screen needs to see it.
     *
     * <p>Returns the lines with what remains returnable rather than what was
     * sold. Those differ the moment a partial return exists, and showing the
     * sold figure would let someone enter a quantity the server then refuses —
     * making them discover the real limit by being told no.
     */
    @Transactional(readOnly = true)
    public ReturnableLineResponse.Invoice returnableFor(Long saleId) {
        PosTransactionMst sale = findSale(saleId);
        List<PosTransactionDtl> lines = dtlRepo.findByPidTranMst(saleId);

        Map<Long, String> productNames = productNames(lines);
        BigDecimal invoiceDiscPct = nvl(sale.getInvoiceDisPct());

        List<ReturnableLineResponse> rows = lines.stream()
                .map(d -> toReturnableRow(d, invoiceDiscPct, productNames))
                .toList();

        String blocked = blockedReason(sale, rows);

        return ReturnableLineResponse.Invoice.builder()
                .saleId(sale.getPidTranMst())
                .invoiceNo(sale.getInvoiceNo())
                .transactionDate(sale.getTransactionDate())
                .customerId(sale.getPidSupCust())
                .customerName(customerName(sale.getPidSupCust()))
                .stage(sale.getStage())
                .paymentType(sale.getPaymentType())
                .invoiceDiscPct(invoiceDiscPct)
                .amountTobePaid(sale.getAmountTobePaid())
                .returnable(blocked == null)
                .blockedReason(blocked)
                .priorReturns(priorReturns(saleId))
                .lines(rows)
                .build();
    }

    private ReturnableLineResponse toReturnableRow(PosTransactionDtl d, BigDecimal invoiceDiscPct,
                                                   Map<Long, String> productNames) {
        BigDecimal sold = nvl(d.getQuantity());
        BigDecimal done = nvl(d.getReturnQty());
        BigDecimal left = sold.subtract(done).max(BigDecimal.ZERO);

        return ReturnableLineResponse.builder()
                .lineId(d.getPidTranDtl())
                .productId(d.getPidProduct())
                .productName(productNames.get(d.getPidProduct()))
                .batchNo(d.getBatchNo())
                .expiryDate(d.getExpiryDate())
                .salesUnit(d.getSalesUnit())
                .noOfPcs(d.getNoOfPcs())
                .soldQty(sold)
                .alreadyReturned(done)
                .returnableQty(left)
                .salesPrice(d.getSalesPrice())
                .discountPct(d.getDiscountPct())
                .unitPrice(perUnit(d))
                .refundPerUnit(refundPerUnit(d, invoiceDiscPct))
                .expired(d.getExpiryDate() != null && d.getExpiryDate().isBefore(LocalDate.now()))
                .build();
    }

    /** Null when the invoice can be returned against; otherwise why not. */
    private String blockedReason(PosTransactionMst sale, List<ReturnableLineResponse> rows) {
        if (!RETURNABLE_STAGES.contains(sale.getStage())) {
            return "Invoice " + sale.getInvoiceNo() + " is at stage '" + sale.getStage()
                 + "' — nothing has left the shelf yet, so there is nothing to return. "
                 + "Edit the invoice instead.";
        }
        boolean anythingLeft = rows.stream()
                .anyMatch(r -> r.getReturnableQty().compareTo(BigDecimal.ZERO) > 0);
        if (!anythingLeft) {
            return "Every line on invoice " + sale.getInvoiceNo() + " has already been returned in full.";
        }
        return null;
    }

    private List<ReturnableLineResponse.PriorReturn> priorReturns(Long saleId) {
        return mstRepo.findByPidTranMstReturnOrderByPidTranMstDesc(saleId).stream()
                .map(r -> ReturnableLineResponse.PriorReturn.builder()
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
    public TransactionResponse createReturn(SalesReturnRequest req) {
        PosTransactionMst sale = findSale(req.getOriginalSaleId());

        if (!RETURNABLE_STAGES.contains(sale.getStage())) {
            throw new BusinessException(
                    "Invoice " + sale.getInvoiceNo() + " is at stage '" + sale.getStage()
                    + "' — nothing has left the shelf yet. Edit the invoice instead of returning against it.",
                    HttpStatus.CONFLICT, "NOTHING_DISPATCHED");
        }

        Long branchId = sale.getPidBranch();
        if (branchId == null) {
            throw new BusinessException(
                    "Invoice " + sale.getInvoiceNo() + " has no branch recorded, so the stock "
                    + "cannot be put back anywhere.", HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }

        // Sale lines by id, so each requested line is matched to the invoice it
        // claims to belong to rather than trusted to.
        Map<Long, PosTransactionDtl> saleLines = dtlRepo.findByPidTranMst(sale.getPidTranMst())
                .stream().collect(Collectors.toMap(PosTransactionDtl::getPidTranDtl, d -> d));

        List<Requested> requested = validateLines(req, sale, saleLines);

        /* ── the return header ─────────────────────────────────────────── */
        PosTransactionMst ret = new PosTransactionMst();
        ret.setPidOrg(sale.getPidOrg());
        ret.setPidCompany(sale.getPidCompany());
        ret.setPidBranch(branchId);
        ret.setPidSupCust(sale.getPidSupCust());
        ret.setTransactionType("SR");
        ret.setInvoiceNo(nextInvoiceNo());
        ret.setTransactionDate(req.getTransactionDate() != null ? req.getTransactionDate() : LocalDate.now());
        ret.setPaymentType(req.getPaymentType() != null ? req.getPaymentType() : sale.getPaymentType());
        // The header discount is already baked into each line's refund, so it is
        // recorded for reference but must not be applied a second time.
        ret.setInvoiceDisPct(BigDecimal.ZERO);
        ret.setRemarks(buildRemarks(req, sale));
        ret.setStage("Confirmed");
        ret.setStatus("Y");
        ret.setFromSource("API");
        ret.setPidTranMstReturn(sale.getPidTranMst());

        PosTransactionMst savedReturn = mstRepo.save(ret);

        /* ── the return lines ──────────────────────────────────────────── */
        BigDecimal refundTotal = BigDecimal.ZERO;
        List<PosTransactionDtl> written = new ArrayList<>();

        for (Requested r : requested) {
            PosTransactionDtl src = r.saleLine();
            BigDecimal refund = r.refund();

            PosTransactionDtl line = new PosTransactionDtl();
            line.setPidTranMst(savedReturn.getPidTranMst());
            line.setPidProduct(src.getPidProduct());
            line.setQuantity(r.quantity());
            // Copied, not recomputed. The goods go back to the lot they came
            // from, at the price they left at — both live on the sale line.
            line.setBatchNo(src.getBatchNo());
            line.setExpiryDate(src.getExpiryDate());
            line.setSalesUnit(src.getSalesUnit());
            line.setSalesType(src.getSalesType());
            line.setUnitMode(src.getUnitMode());
            line.setNoOfPcs(src.getNoOfPcs());
            line.setSalesPrice(src.getSalesPrice());
            line.setMrpPrice(src.getMrpPrice());
            line.setMnpPrice(src.getMnpPrice());
            line.setDiscountPct(src.getDiscountPct());
            line.setTotalPrice(refund);
            line.setReturnQty(BigDecimal.ZERO);
            line.setPidTranDtlReturn(src.getPidTranDtl());

            written.add(dtlRepo.save(line));
            refundTotal = refundTotal.add(refund);

            // The sale line now carries how much of it has come back. This is
            // what stops the same goods being returned twice.
            src.setReturnQty(nvl(src.getReturnQty()).add(r.quantity()));
            dtlRepo.save(src);
        }

        savedReturn.setTotalAmount(refundTotal);
        savedReturn.setAmountTobePaid(refundTotal);
        savedReturn.setInvoiceDisAmt(BigDecimal.ZERO);
        mstRepo.save(savedReturn);

        // Stock last, once the document is whole. Posting mid-way would leave
        // goods on the shelf against a return that then failed to save.
        stockPostingService.post(savedReturn.getPidTranMst());
        accounting.salesReturnCreated(savedReturn);

        log.info("Sales return {} against {}: {} line(s), refund {}",
                savedReturn.getInvoiceNo(), sale.getInvoiceNo(), written.size(), refundTotal);

        return buildResponse(savedReturn, written, customerName(sale.getPidSupCust()), sale.getInvoiceNo());
    }

    /** One validated return line, with the refund already worked out. */
    private record Requested(PosTransactionDtl saleLine, BigDecimal quantity, BigDecimal refund) {}

    private List<Requested> validateLines(SalesReturnRequest req, PosTransactionMst sale,
                                          Map<Long, PosTransactionDtl> saleLines) {
        BigDecimal invoiceDiscPct = nvl(sale.getInvoiceDisPct());
        Map<Long, BigDecimal> perLine = new LinkedHashMap<>();

        // Two requests naming the same line are summed before checking, not
        // checked separately — otherwise each passes on its own and together
        // they return more than was sold.
        for (SalesReturnRequest.Line l : req.getLines()) {
            perLine.merge(l.getOriginalLineId(), l.getQuantity(), BigDecimal::add);
        }

        List<Requested> out = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : perLine.entrySet()) {
            PosTransactionDtl src = saleLines.get(e.getKey());
            if (src == null) {
                throw new ValidationException(
                        "Line " + e.getKey() + " is not part of invoice " + sale.getInvoiceNo() + ".");
            }

            BigDecimal want = e.getValue();
            BigDecimal sold = nvl(src.getQuantity());
            BigDecimal done = nvl(src.getReturnQty());
            BigDecimal left = sold.subtract(done);

            if (want.compareTo(left) > 0) {
                String product = productName(src.getPidProduct());
                throw new ValidationException(done.compareTo(BigDecimal.ZERO) > 0
                        ? product + ": only " + strip(left) + " of " + strip(sold) + " "
                          + unitLabel(src) + " can still be returned — " + strip(done)
                          + " already came back."
                        : product + ": only " + strip(sold) + " " + unitLabel(src) + " were sold.");
            }

            if (src.getBatchNo() == null || src.getBatchNo().isBlank()) {
                throw new BusinessException(
                        productName(src.getPidProduct()) + " was invoiced without a batch number, so "
                        + "there is no lot to put it back into. Correct the invoice first.",
                        HttpStatus.CONFLICT, "NO_BATCH_ON_SALE_LINE");
            }

            out.add(new Requested(src, want, refundFor(src, want, invoiceDiscPct)));
        }
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       MONEY
       ══════════════════════════════════════════════════════════════════════ */

    /** What one unit fetched, after the line's own discount. */
    private static BigDecimal perUnit(PosTransactionDtl d) {
        BigDecimal qty = nvl(d.getQuantity());
        if (qty.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return nvl(d.getTotalPrice()).divide(qty, 6, RoundingMode.HALF_UP);
    }

    /**
     * What returning one unit hands back.
     *
     * <p>Net of the invoice-level discount, because that is what the customer
     * actually paid. Refunding the gross line price returns money that was never
     * taken — a small overpayment on every discounted invoice, in the direction
     * nobody notices until the till is short.
     */
    private static BigDecimal refundPerUnit(PosTransactionDtl d, BigDecimal invoiceDiscPct) {
        return netOfInvoiceDiscount(perUnit(d), invoiceDiscPct).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal refundFor(PosTransactionDtl d, BigDecimal qty, BigDecimal invoiceDiscPct) {
        BigDecimal gross = perUnit(d).multiply(qty);
        return netOfInvoiceDiscount(gross, invoiceDiscPct).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal netOfInvoiceDiscount(BigDecimal amount, BigDecimal pct) {
        if (pct == null || pct.compareTo(BigDecimal.ZERO) <= 0) return amount;
        BigDecimal share = amount.multiply(pct).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return amount.subtract(share);
    }

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(String q, LocalDate from, LocalDate to,
                                          Long branchId, boolean allBranches, Pageable pageable) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        Page<PosTransactionMst> page = mstRepo.findSalesReturns(
                orgId, branchScope.resolve(branchId, allBranches),
                since(from), until(to), (q == null || q.isBlank()) ? "" : q, pageable);

        Map<Long, String> customers = customerNames(page.getContent());
        Map<Long, String> originals = originalInvoiceNos(page.getContent());

        return page.map(m -> buildResponse(m, dtlRepo.findByPidTranMst(m.getPidTranMst()),
                customers.get(m.getPidSupCust()), originals.get(m.getPidTranMstReturn())));
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long id) {
        PosTransactionMst m = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sales return", id));
        if (!"SR".equals(m.getTransactionType())) {
            throw new ResourceNotFoundException("Sales return", id);
        }
        requireSameOrg(m);
        String original = m.getPidTranMstReturn() == null ? null
                : mstRepo.findById(m.getPidTranMstReturn()).map(PosTransactionMst::getInvoiceNo).orElse(null);
        return buildResponse(m, dtlRepo.findByPidTranMst(id), customerName(m.getPidSupCust()), original);
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    private PosTransactionMst findSale(Long saleId) {
        PosTransactionMst m = mstRepo.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", saleId));
        if (!"SL".equals(m.getTransactionType())) {
            // Not-found rather than a type complaint: an id that is not a sales
            // invoice is not a sales invoice this caller can return against.
            throw new ResourceNotFoundException("Sale", saleId);
        }
        requireSameOrg(m);
        return m;
    }

    private void requireSameOrg(PosTransactionMst m) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && m.getPidOrg() != null && !orgId.equals(m.getPidOrg())) {
            throw new ResourceNotFoundException("Sale", m.getPidTranMst());
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
        return documentNumbers.nextInvoiceNo("SR");
    }

    private String buildRemarks(SalesReturnRequest req, PosTransactionMst sale) {
        StringBuilder sb = new StringBuilder("Return against ").append(sale.getInvoiceNo());
        if (req.getReason() != null && !req.getReason().isBlank()) {
            sb.append(" — ").append(req.getReason().trim());
        }
        if (req.getRemarks() != null && !req.getRemarks().isBlank()) {
            sb.append(". ").append(req.getRemarks().trim());
        }
        return sb.toString();
    }

    private TransactionResponse buildResponse(PosTransactionMst m, List<PosTransactionDtl> lines,
                                              String customerName, String originalInvoiceNo) {
        Map<Long, String> names = productNames(lines);

        var lineResp = lines.stream().map(d -> TransactionResponse.TransactionLineResponse.builder()
                .lineId(d.getPidTranDtl())
                .productId(d.getPidProduct())
                .productName(names.get(d.getPidProduct()))
                .batchNo(d.getBatchNo())
                .expiryDate(d.getExpiryDate())
                .quantity(d.getQuantity())
                .salesUnit(d.getSalesUnit())
                .salesPrice(d.getSalesPrice())
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
                .stage(m.getStage())
                .status(m.getStatus())
                .customerId(m.getPidSupCust())
                .customerName(customerName)
                .branchId(m.getPidBranch())
                .subtotal(m.getTotalAmount())
                .amountTobePaid(m.getAmountTobePaid())
                // The invoice this reverses, so a return is never orphaned on screen.
                .challanNo(originalInvoiceNo)
                .remarks(m.getRemarks())
                .lines(lineResp)
                .build();
    }

    private Map<Long, String> productNames(List<PosTransactionDtl> lines) {
        if (lines.isEmpty()) return Map.of();
        return productRepo.findAllById(lines.stream()
                        .map(PosTransactionDtl::getPidProduct).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(
                        PosProductInfo::getPidProduct, PosProductInfo::getProductName, (a, b) -> a));
    }

    private Map<Long, String> customerNames(List<PosTransactionMst> masters) {
        var ids = masters.stream().map(PosTransactionMst::getPidSupCust)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return customerRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(c -> c.getPidCustomer(), c -> c.getCustomerName(), (a, b) -> a));
    }

    private Map<Long, String> originalInvoiceNos(List<PosTransactionMst> returns) {
        var ids = returns.stream().map(PosTransactionMst::getPidTranMstReturn)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return mstRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(PosTransactionMst::getPidTranMst,
                                          PosTransactionMst::getInvoiceNo, (a, b) -> a));
    }

    private String customerName(Long id) {
        return id == null ? null
                : customerRepo.findById(id).map(c -> c.getCustomerName()).orElse(null);
    }

    private String productName(Long id) {
        return productRepo.findById(id).map(PosProductInfo::getProductName).orElse("Product " + id);
    }

    private static String unitLabel(PosTransactionDtl d) {
        return d.getSalesUnit() == null ? "unit" : d.getSalesUnit().toLowerCase();
    }

    /** 2.00 → "2", so a message reads "only 2 box" rather than "only 2.00 box". */
    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
