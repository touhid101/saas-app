package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.SalesMergeResponse;
import com.friendspharma.app.entity.PosCustomerInfo;
import com.friendspharma.app.entity.PosInvoiceMerge;
import com.friendspharma.app.entity.PosInvoiceMergeDtl;
import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.PosCustomerInfoRepository;
import com.friendspharma.app.repository.PosInvoiceMergeRepository;
import com.friendspharma.app.repository.PosTransactionDtlRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Combining a customer's open orders into one document for delivery and
 * collection.
 *
 * <p><b>What a merge is, and what it deliberately is not.</b> The merged
 * invoice is a grouping document. It carries the combined total and a copy of
 * every line underneath it, so it can be printed, delivered and collected as
 * one — and it moves no stock whatsoever.
 *
 * <p>That last restraint is the whole design. The source orders have already
 * allocated their batches; posting the copies would consume the same stock
 * twice. The copies exist to be read — on the delivery note and the customer's
 * invoice — never to be counted. Every report that totals sales money or
 * quantity skips the wrapper on {@code IS_MERGE_INVOICE}, and the profit
 * reports are driven from stock movements, which the wrapper never writes. So
 * the sources remain the accounting record and the merge sits above them:
 *
 * <ul>
 *   <li>The merged invoice is flagged {@code IS_MERGE_INVOICE = 'Y'}, which is
 *       what every report filters on. A new report that reads
 *       POS_TRANSACTION_DTL without that filter will double-count the goods.</li>
 *   <li>Each source points at it through {@code MERGE_PID_TRAN_MST}.</li>
 *   <li>Sales Activity shows the merge and hides the sources — one round, one
 *       delivery, one collection.</li>
 *   <li>The invoice list still shows the sources, read-only: they are what the
 *       goods actually went out on, and they must stay auditable.</li>
 *   <li>Revenue reports skip the wrapper, or the same money is counted twice.</li>
 * </ul>
 *
 * <p>Only today's unconfirmed orders can be merged. Once an order is confirmed
 * it is on its way — regrouping it would move goods a customer has already been
 * told about — and yesterday's orders belong to a round that has been run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesMergeServiceImpl {

    private final PosTransactionMstRepository mstRepo;
    private final PosTransactionDtlRepository dtlRepo;
    private final PosInvoiceMergeRepository mergeRepo;
    private final PosCustomerInfoRepository customerRepo;
    private final DocumentNumberService documentNumbers;

    /** The one stage at which an order has not yet gone anywhere. */
    private static final String STAGE_SUBMITTED = "Submitted";
    private static final String MERGED_FLAG = "Y";
    private static final String ACTIVE = "Y";
    private static final String CANCELLED = "C";

    /* ══════════════════════════════════════════════════════════════════════
       WHAT CAN BE MERGED
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Today's unconfirmed orders for one customer that are not already merged.
     *
     * <p>Each of the three conditions removes a way of merging something that
     * should not be: a confirmed order is already promised, yesterday's belongs
     * to a round that has run, and one already in a merge would be counted in
     * two places at once.
     */
    @Transactional(readOnly = true)
    public List<SalesMergeResponse.Candidate> eligible(Long customerId) {
        Long orgId = requireOrg();
        if (customerId == null) throw new ValidationException("Choose a customer first.");

        List<PosTransactionMst> open = mstRepo.findMergeCandidates(
                orgId, customerId, STAGE_SUBMITTED, LocalDate.now());

        Map<Long, Integer> lineCounts = lineCounts(open);

        return open.stream()
                .map(t -> SalesMergeResponse.Candidate.builder()
                        .id(t.getPidTranMst())
                        .invoiceNo(t.getInvoiceNo())
                        .transactionDate(t.getTransactionDate())
                        .stage(t.getStage())
                        .paymentType(t.getPaymentType())
                        .lineCount(lineCounts.getOrDefault(t.getPidTranMst(), 0))
                        .subtotal(nvl(t.getTotalAmount()))
                        .amount(nvl(t.getAmountTobePaid()))
                        .paid(nvl(t.getGivenAmount()))
                        .build())
                .toList();
    }

    /* ══════════════════════════════════════════════════════════════════════
       MERGE
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public SalesMergeResponse.Merge merge(Long customerId, List<Long> orderIds, String remarks) {
        Long orgId = requireOrg();

        if (orderIds == null || orderIds.size() < 2) {
            throw new ValidationException(
                    "A merge needs at least two orders — one order merged with nothing is just "
                    + "that order.");
        }

        List<Long> distinct = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.size() < orderIds.size()) {
            throw new ValidationException("The same order was selected more than once.");
        }

        List<PosTransactionMst> orders = new ArrayList<>();
        for (Long id : distinct) {
            PosTransactionMst t = mstRepo.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Order", id));
            requireMergeable(t, orgId, customerId);
            orders.add(t);
        }

        PosCustomerInfo customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));

        PosTransactionMst first = orders.get(0);
        BigDecimal total = orders.stream()
                .map(t -> nvl(t.getAmountTobePaid())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = orders.stream()
                .map(t -> nvl(t.getGivenAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);

        /* ── the grouping invoice ── */

        PosTransactionMst merged = new PosTransactionMst();
        merged.setPidOrg(orgId);
        merged.setPidCompany(first.getPidCompany());
        merged.setPidBranch(first.getPidBranch());
        merged.setPidSupCust(customerId);
        merged.setTransactionType("SL");
        merged.setInvoiceNo(documentNumbers.nextInvoiceNo("SL"));
        merged.setTransactionDate(LocalDate.now());
        merged.setPaymentType(first.getPaymentType());
        merged.setStage(STAGE_SUBMITTED);
        merged.setStatus("Y");
        merged.setFromSource("MERGE");
        merged.setTotalAmount(total);
        merged.setAmountTobePaid(total);
        merged.setGivenAmount(paid);
        merged.setPaymentStatus(paid.compareTo(total) >= 0 ? "Paid"
                : paid.compareTo(BigDecimal.ZERO) > 0 ? "Partial" : "Unpaid");
        // The flag that keeps this document out of revenue reporting: the money
        // is already counted on the orders underneath it.
        merged.setIsMergeInvoice(MERGED_FLAG);
        merged.setRemarks(buildRemarks(orders, remarks));
        merged.setDeliveryAddress(first.getDeliveryAddress());

        PosTransactionMst saved = mstRepo.save(merged);

        /* ── the lines, copied so the document can be printed ── */

        // Read-only duplicates. No stock is posted against them: the batches
        // were allocated when the source orders were raised, and posting again
        // would issue the same pieces a second time.
        for (PosTransactionMst t : orders) {
            for (PosTransactionDtl src : dtlRepo.findByPidTranMst(t.getPidTranMst())) {
                dtlRepo.save(copyLine(src, saved.getPidTranMst()));
            }
        }

        /* ── the record of what went into it ── */

        PosInvoiceMerge record = PosInvoiceMerge.builder()
                .mergeNo(saved.getInvoiceNo())
                .pidOrg(orgId)
                .pidBranch(first.getPidBranch())
                .pidCustomer(customerId)
                .customerName(customer.getCustomerName())
                .mergeDate(LocalDate.now())
                .totalAmount(total)
                .remarks(remarks == null || remarks.isBlank() ? null : remarks.trim())
                .build();
        record.setStatus(ACTIVE);

        for (PosTransactionMst t : orders) {
            record.getDetails().add(PosInvoiceMergeDtl.builder()
                    .merge(record)
                    .pidTransaction(t.getPidTranMst())
                    .invoiceNo(t.getInvoiceNo())
                    .invoiceAmount(nvl(t.getAmountTobePaid()))
                    .build());

            // The source keeps its lines, its stock and its money. It simply
            // now belongs to a round that is run under the merged document.
            t.setMergePidTranMst(saved.getPidTranMst());
            mstRepo.save(t);
        }

        mergeRepo.save(record);

        log.info("Merged {} order(s) for {} into {} — {}",
                orders.size(), customer.getCustomerName(), saved.getInvoiceNo(), total);

        return detail(saved.getPidTranMst());
    }

    private void requireMergeable(PosTransactionMst t, Long orgId, Long customerId) {
        if (orgId != null && t.getPidOrg() != null && !orgId.equals(t.getPidOrg())) {
            throw new ResourceNotFoundException("Order", t.getPidTranMst());
        }
        if (!"SL".equals(t.getTransactionType())) {
            throw new ValidationException(t.getInvoiceNo() + " is not a sales order.");
        }
        if (!Objects.equals(t.getPidSupCust(), customerId)) {
            throw new ValidationException(
                    t.getInvoiceNo() + " belongs to a different customer. A merge is one customer's "
                    + "orders combined into one delivery.");
        }
        if (!STAGE_SUBMITTED.equalsIgnoreCase(t.getStage())) {
            throw new BusinessException(
                    t.getInvoiceNo() + " is at stage '" + t.getStage() + "' and has already been "
                    + "confirmed. Merging it would regroup goods the customer has been told about.",
                    HttpStatus.CONFLICT, "ORDER_ALREADY_CONFIRMED");
        }
        if (!LocalDate.now().equals(t.getTransactionDate())) {
            throw new BusinessException(
                    t.getInvoiceNo() + " is dated " + t.getTransactionDate() + ". Only today's "
                    + "orders can be merged — an earlier one belongs to a round that has been run.",
                    HttpStatus.CONFLICT, "ORDER_NOT_TODAY");
        }
        if (t.getMergePidTranMst() != null) {
            throw new BusinessException(
                    t.getInvoiceNo() + " is already part of a merge.",
                    HttpStatus.CONFLICT, "ORDER_ALREADY_MERGED");
        }
        if (MERGED_FLAG.equals(t.getIsMergeInvoice())) {
            throw new ValidationException(
                    t.getInvoiceNo() + " is itself a merged invoice and cannot be merged again.");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       UNDO
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Splits a merge back into its orders.
     *
     * <p>Allowed only while the merged document is still unconfirmed, for the
     * same reason a confirmed order cannot be merged: past that point a round
     * has been planned around it.
     */
    @Transactional
    public void unmerge(Long mergedId, String reason) {
        Long orgId = requireOrg();
        PosTransactionMst merged = mstRepo.findById(mergedId)
                .orElseThrow(() -> new ResourceNotFoundException("Merged invoice", mergedId));
        if (orgId != null && merged.getPidOrg() != null && !orgId.equals(merged.getPidOrg())) {
            throw new ResourceNotFoundException("Merged invoice", mergedId);
        }
        if (!MERGED_FLAG.equals(merged.getIsMergeInvoice())) {
            throw new ValidationException(merged.getInvoiceNo() + " is not a merged invoice.");
        }
        if (!STAGE_SUBMITTED.equalsIgnoreCase(merged.getStage())) {
            throw new BusinessException(
                    merged.getInvoiceNo() + " is at stage '" + merged.getStage() + "' and can no "
                    + "longer be split. The delivery has already been arranged around it.",
                    HttpStatus.CONFLICT, "MERGE_ALREADY_CONFIRMED");
        }

        List<PosTransactionMst> sources = mstRepo.findByMergePidTranMst(mergedId);
        for (PosTransactionMst t : sources) {
            t.setMergePidTranMst(null);
            mstRepo.save(t);
        }

        // The wrapper's lines were only ever copies for printing. The orders
        // still hold the originals, so deleting these loses nothing — and
        // leaving them would show a cancelled document full of goods.
        dtlRepo.deleteAll(dtlRepo.findByPidTranMst(mergedId));

        mergeRepo.findByMergeNo(merged.getInvoiceNo()).ifPresent(r -> {
            r.setStatus(CANCELLED);
            r.setCancelledReason(reason == null || reason.isBlank() ? null : reason.trim());
            mergeRepo.save(r);
        });

        // The wrapper is cancelled rather than deleted: it was issued a number
        // and somebody may have written it down.
        merged.setStatus("N");
        merged.setStage("Cancelled");
        // The reason belongs on the merge record, which has room for it. Here
        // there is only a fixed marker: appending to remarks the merge had
        // already filled to 100 characters is what broke the split.
        merged.setRemarks(clipRemarks("Split back into its orders. "
                + (merged.getRemarks() == null ? "" : merged.getRemarks())));
        mstRepo.save(merged);

        log.info("Merge {} split back into {} order(s)", merged.getInvoiceNo(), sources.size());
    }

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<SalesMergeResponse.Merge> list(LocalDate from, LocalDate to) {
        return mstRepo.findMergedInvoices(requireOrg(), from, to).stream()
                .map(t -> detail(t.getPidTranMst()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SalesMergeResponse.Merge detail(Long mergedId) {
        PosTransactionMst merged = mstRepo.findById(mergedId)
                .orElseThrow(() -> new ResourceNotFoundException("Merged invoice", mergedId));

        List<PosTransactionMst> sources = mstRepo.findByMergePidTranMst(mergedId);
        // A split clears the link, so a cancelled merge would otherwise report
        // itself as holding nothing — losing exactly the history it was kept
        // for. Its own detail rows survive the split and still name the orders.
        if (sources.isEmpty()) {
            sources = mergeRepo.findByMergeNo(merged.getInvoiceNo())
                    .map(r -> r.getDetails().stream()
                            .map(PosInvoiceMergeDtl::getPidTransaction)
                            .filter(Objects::nonNull)
                            .map(id -> mstRepo.findById(id).orElse(null))
                            .filter(Objects::nonNull)
                            .toList())
                    .orElse(List.of());
        }
        Map<Long, Integer> lineCounts = lineCounts(sources);
        String customerName = merged.getPidSupCust() == null ? null
                : customerRepo.findById(merged.getPidSupCust())
                        .map(PosCustomerInfo::getCustomerName).orElse(null);

        return SalesMergeResponse.Merge.builder()
                .id(merged.getPidTranMst())
                .invoiceNo(merged.getInvoiceNo())
                .mergeDate(merged.getTransactionDate())
                .customerId(merged.getPidSupCust())
                .customerName(customerName)
                .stage(merged.getStage())
                .status(merged.getStatus())
                .cancelled(!"Y".equals(merged.getStatus()))
                .total(nvl(merged.getAmountTobePaid()))
                .paid(nvl(merged.getGivenAmount()))
                .remarks(merged.getRemarks())
                .sources(sources.stream()
                        .map(t -> SalesMergeResponse.Candidate.builder()
                                .id(t.getPidTranMst())
                                .invoiceNo(t.getInvoiceNo())
                                .transactionDate(t.getTransactionDate())
                                .stage(t.getStage())
                                .paymentType(t.getPaymentType())
                                .lineCount(lineCounts.getOrDefault(t.getPidTranMst(), 0))
                                .subtotal(nvl(t.getTotalAmount()))
                                .amount(nvl(t.getAmountTobePaid()))
                                .paid(nvl(t.getGivenAmount()))
                                .build())
                        .toList())
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * A line on the merged document mirroring one on a source order.
     *
     * <p>Everything the invoice prints is carried over — product, quantity,
     * unit, price, batch and expiry — but not the identity: a new PID_TRAN_DTL
     * is generated, and PID_TRAN_DTL_RETURN is left null. Stock movements point
     * at the source line through REF_DTL, so the copy is reachable by nothing
     * that moves or values stock.
     */
    private PosTransactionDtl copyLine(PosTransactionDtl src, Long mergedId) {
        return PosTransactionDtl.builder()
                .pidTranMst(mergedId)
                .pidProduct(src.getPidProduct())
                .quantity(src.getQuantity())
                .mnpPrice(src.getMnpPrice())
                .mrpPrice(src.getMrpPrice())
                .discountPct(src.getDiscountPct())
                .flatDiscount(src.getFlatDiscount())
                .totalPrice(src.getTotalPrice())
                .expiryDate(src.getExpiryDate())
                .salesPrice(src.getSalesPrice())
                .tpPct(src.getTpPct())
                .igta(src.getIgta())
                .noOfPcs(src.getNoOfPcs())
                .salesUnit(src.getSalesUnit())
                .stripBox(src.getStripBox())
                .salesType(src.getSalesType())
                .batchNo(src.getBatchNo())
                .unitMode(src.getUnitMode())
                .insertedDate(LocalDateTime.now())
                .build();
    }

    /**
     * REMARKS is VARCHAR(100), and a split used to append its note to a value
     * the merge had already filled to the limit — which failed the whole split
     * on a database constraint rather than saying anything useful.
     */
    private static String clipRemarks(String text) {
        if (text == null) return null;
        String t = text.trim();
        return t.length() <= REMARKS_MAX ? t : t.substring(0, REMARKS_MAX);
    }

    /** The width of POS_TRANSACTION_MST.REMARKS. */
    private static final int REMARKS_MAX = 100;

    private Map<Long, Integer> lineCounts(List<PosTransactionMst> orders) {
        if (orders.isEmpty()) return Map.of();
        return orders.stream().collect(Collectors.toMap(
                PosTransactionMst::getPidTranMst,
                t -> dtlRepo.findByPidTranMst(t.getPidTranMst()).size(),
                (a, b) -> a));
    }

    private String buildRemarks(List<PosTransactionMst> orders, String remarks) {
        String list = orders.stream().map(PosTransactionMst::getInvoiceNo)
                .collect(Collectors.joining(", "));
        String base = "Merged: " + list;
        // Truncated to the column, keeping the invoice list rather than the
        // free text — the list is what makes the document reconcilable.
        String full = (remarks == null || remarks.isBlank()) ? base : base + " — " + remarks.trim();
        return clipRemarks(full);
    }

    private Long requireOrg() {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) throw new ValidationException("Your account has no organisation assigned.");
        return orgId;
    }

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
