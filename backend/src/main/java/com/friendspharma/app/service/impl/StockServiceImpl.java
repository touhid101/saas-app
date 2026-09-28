package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.StockResponses.AllocationLine;
import com.friendspharma.app.dto.response.StockResponses.StockAllocation;
import com.friendspharma.app.entity.PosBoxInfo;
import com.friendspharma.app.entity.PosLeafInfo;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.entity.PosStockBatch;
import com.friendspharma.app.entity.PosStockMovement;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.InsufficientStockException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.PosBoxInfoRepository;
import com.friendspharma.app.repository.PosLeafInfoRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.repository.PosStockBatchRepository;
import com.friendspharma.app.repository.PosStockMovementRepository;
import com.friendspharma.app.service.interfaces.StockService;
import com.friendspharma.app.util.StockUnitConverter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * The single write path for stock.
 *
 * <p>Balance and movement are written together, in one transaction, always. The
 * balance in {@code POS_STOCK_BATCH} is a cache; {@code POS_STOCK_MOVEMENT} is
 * the truth, and the reconciliation query exists to prove they still agree.
 *
 * <p>Allocation is <b>batch-wise FEFO</b>: whole lots, earliest expiry first.
 * The legacy {@code PROC_EXPIRY_WISE_SALES_QTY_DIS} grouped by expiry date
 * instead, so two deliveries sharing an expiry merged into one bucket and their
 * individual costs were lost. Cost is per lot here, which is what makes real
 * COGS — and therefore actual profit — computable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private final PosStockBatchRepository    batchRepo;
    private final PosStockMovementRepository movementRepo;
    private final PosProductInfoRepository   productRepo;
    private final PosBoxInfoRepository       boxRepo;
    private final PosLeafInfoRepository      leafRepo;

    /* Lots are debited with a conditional UPDATE that the persistence context
       knows nothing about (see PosStockBatchRepository#deductIfAvailable), so
       every deduction is followed by a refresh before the entity is read. */
    @PersistenceContext
    private EntityManager em;

    /* ══════════════════════════════════════════════════════════════════════
       INBOUND
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public BigDecimal receive(ReceiveCommand cmd) {
        requireBranch(cmd.branchId());
        BigDecimal qty = requirePositive(cmd.qtyPcs(), "received quantity");
        String batchNo = requireBatchNo(cmd.batchNo());
        String movementType = cmd.movementType() != null ? cmd.movementType() : PosStockMovement.PURCHASE;

        // Idempotency. Re-approving an invoice used to add its quantities a
        // second time; nobody notices until a physical count disagrees, by
        // which point the history needed to explain it is gone.
        if (cmd.refDtl() != null && cmd.refType() != null
                && movementRepo.existsByRefTypeAndRefDtlAndMovementType(
                        cmd.refType(), cmd.refDtl(), movementType)) {
            log.debug("Stock receipt for {} line {} already posted — skipping",
                    cmd.refType(), cmd.refDtl());
            return batchRepo
                    .findByPidBranchAndPidProductAndBatchNo(cmd.branchId(), cmd.productId(), batchNo)
                    .map(PosStockBatch::getQtyPcs)
                    .orElse(BigDecimal.ZERO);
        }

        PosStockBatch batch = batchRepo
                .findByPidBranchAndPidProductAndBatchNo(cmd.branchId(), cmd.productId(), batchNo)
                .orElse(null);

        if (batch == null) {
            batch = new PosStockBatch();
            batch.setPidOrg(cmd.orgId());
            batch.setPidBranch(cmd.branchId());
            batch.setPidProduct(cmd.productId());
            batch.setBatchNo(batchNo);
            batch.setExpiryDate(cmd.expiryDate());
            batch.setQtyPcs(BigDecimal.ZERO);
            batch.setReceivedPcs(BigDecimal.ZERO);
            batch.setIssuedPcs(BigDecimal.ZERO);
            batch.setPcsPerBox(cmd.pcsPerBox());
            batch.setPcsPerStrip(cmd.pcsPerStrip());
            batch.setReceivedDate(cmd.receivedDate() != null ? cmd.receivedDate() : LocalDate.now());
            batch.setSourceTranMst(cmd.refMst());
            batch.setSourceTranDtl(cmd.refDtl());
            batch.setStatus(PosStockBatch.ACTIVE);
            batch.setCostPerPcs(cmd.costPerPcs());
            batch.setMrpPrice(cmd.mrpPrice());
        } else {
            // A second delivery into an existing lot re-averages its cost. Two
            // receipts at different prices are genuinely one lot of stock at a
            // blended cost — keeping the first price would understate or
            // overstate every piece sold from it afterwards.
            batch.setCostPerPcs(blendCost(batch, qty, cmd.costPerPcs()));
            if (cmd.mrpPrice() != null) batch.setMrpPrice(cmd.mrpPrice());

            // An expiry already on the lot is not overwritten: the earliest one
            // governs, because that is when the shelf has to be cleared.
            if (batch.getExpiryDate() == null) {
                batch.setExpiryDate(cmd.expiryDate());
            } else if (cmd.expiryDate() != null && cmd.expiryDate().isBefore(batch.getExpiryDate())) {
                batch.setExpiryDate(cmd.expiryDate());
            }
            if (batch.getPcsPerBox() == null)   batch.setPcsPerBox(cmd.pcsPerBox());
            if (batch.getPcsPerStrip() == null) batch.setPcsPerStrip(cmd.pcsPerStrip());
            if (PosStockBatch.EXHAUSTED.equals(batch.getStatus())) {
                batch.setStatus(PosStockBatch.ACTIVE);
            }
        }

        batch.setQtyPcs(nvl(batch.getQtyPcs()).add(qty));
        batch.setReceivedPcs(nvl(batch.getReceivedPcs()).add(qty));
        PosStockBatch saved = batchRepo.save(batch);

        writeMovement(saved, movementType, PosStockMovement.IN, qty,
                cmd.costPerPcs() != null ? cmd.costPerPcs() : saved.getCostPerPcs(),
                cmd.refType(), cmd.refMst(), cmd.refDtl(),
                cmd.receivedDate() != null ? cmd.receivedDate() : LocalDate.now(),
                cmd.remarks());

        log.debug("Stock IN  branch={} product={} batch={} +{} pcs (balance {})",
                cmd.branchId(), cmd.productId(), batchNo, qty, saved.getQtyPcs());
        return saved.getQtyPcs();
    }

    /* ══════════════════════════════════════════════════════════════════════
       OUTBOUND — batch-wise FEFO
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public StockAllocation issue(IssueCommand cmd) {
        requireBranch(cmd.branchId());
        PosProductInfo product = loadProduct(cmd.productId());
        String unit = StockUnitConverter.normalise(cmd.unit());
        Packing packing = packingFor(product);

        BigDecimal requestedPcs = toPcs(cmd.qty(), unit, packing, product.getProductName());
        requirePositive(requestedPcs, "issue quantity");

        String movementType = cmd.movementType() != null ? cmd.movementType() : PosStockMovement.SALE;

        // Same guard as receive(): a document posted twice must move stock once.
        // Without it, re-approving a sale would deduct the quantity again and
        // the shortfall would only surface at the next physical count.
        if (cmd.refDtl() != null && cmd.refType() != null
                && movementRepo.existsByRefTypeAndRefDtlAndMovementType(
                        cmd.refType(), cmd.refDtl(), movementType)) {
            log.debug("Stock issue for {} line {} already posted — skipping",
                    cmd.refType(), cmd.refDtl());
            return alreadyPosted(cmd, product, unit, requestedPcs);
        }

        // Locked in allocation order. Two concurrent sales would otherwise both
        // read the same availability, both pass the check, and both deduct.
        List<PosStockBatch> lots = batchRepo.lockAllocatableBatches(
                cmd.branchId(), cmd.productId(), LocalDate.now());

        Walk walk = walkBatches(lots, requestedPcs, unitSizeOf(unit, packing));

        if (walk.shortfall().compareTo(BigDecimal.ZERO) > 0) {
            // All or nothing. A partly filled line leaves the invoice and the
            // stock describing different events, and there is no sensible way to
            // present that to someone standing at a counter.
            BigDecimal availablePcs = requestedPcs.subtract(walk.shortfall());
            throw new InsufficientStockException(
                    product.getProductName(), branchLabel(cmd.branchId()), unit,
                    cmd.qty(),
                    StockUnitConverter.fromPcs(availablePcs, unit, packing.pcsPerBox(), packing.pcsPerStrip()),
                    requestedPcs, availablePcs);
        }

        LocalDate when = cmd.movementDate() != null ? cmd.movementDate() : LocalDate.now();

        for (Take take : walk.takes()) {
            PosStockBatch lot = takeFromLot(take.lot(), take.qty(), () -> {
                // The lot emptied between the walk and the write. The row lock
                // above keeps that rare, but a lot loaded earlier in this same
                // transaction is read from the persistence context at its old
                // quantity, and that is not rare at all: every sale checks
                // availability before it posts. Refuse the line rather than
                // write a balance that was computed from a stale figure.
                BigDecimal availablePcs = availablePcs(cmd.branchId(), cmd.productId());
                return new InsufficientStockException(
                        product.getProductName(), branchLabel(cmd.branchId()), unit,
                        cmd.qty(),
                        StockUnitConverter.fromPcs(availablePcs, unit, packing.pcsPerBox(), packing.pcsPerStrip()),
                        requestedPcs, availablePcs);
            });

            writeMovement(lot, movementType, PosStockMovement.OUT, take.qty(),
                    lot.getCostPerPcs(), cmd.refType(), cmd.refMst(), cmd.refDtl(),
                    when, cmd.remarks());
        }

        log.debug("Stock OUT branch={} product={} -{} pcs across {} batch(es)",
                cmd.branchId(), cmd.productId(), requestedPcs, walk.takes().size());

        return buildAllocation(cmd.branchId(), product, packing, cmd.qty(), unit,
                requestedPcs, walk);
    }

    @Override
    @Transactional(readOnly = true)
    public StockAllocation preview(Long branchId, Long productId, BigDecimal qty, String unit) {
        requireBranch(branchId);
        PosProductInfo product = loadProduct(productId);
        String u = StockUnitConverter.normalise(unit);
        Packing packing = packingFor(product);

        BigDecimal requestedPcs = toPcs(qty, u, packing, product.getProductName());

        // Same ordering as issue(), unlocked. A preview that walked the lots in
        // a different order would show an allocation the sale then contradicts.
        List<PosStockBatch> lots = batchRepo.findAllocatableBatches(
                branchId, productId, LocalDate.now(), false);

        return buildAllocation(branchId, product, packing, qty, u, requestedPcs,
                walkBatches(lots, requestedPcs, unitSizeOf(u, packing)));
    }

    /* ══════════════════════════════════════════════════════════════════════
       RETURNS AND REVERSALS
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public BigDecimal issueFromBatch(Long orgId, Long branchId, Long productId, String batchNo,
                                     BigDecimal qtyPcs, String movementType, String refType,
                                     Long refMst, Long refDtl, LocalDate movementDate, String remarks) {
        requireBranch(branchId);
        BigDecimal qty = requirePositive(qtyPcs, "return quantity");
        String batch = requireBatchNo(batchNo);

        // Idempotent on the same line, like receive() — a document approved
        // twice must not take the stock out twice.
        if (refDtl != null && refType != null
                && movementRepo.existsByRefTypeAndRefDtlAndMovementType(refType, refDtl, movementType)) {
            log.debug("Stock issue for {} line {} already posted — skipping", refType, refDtl);
            return batchRepo.findByPidBranchAndPidProductAndBatchNo(branchId, productId, batch)
                    .map(PosStockBatch::getQtyPcs).orElse(BigDecimal.ZERO);
        }

        PosStockBatch lot = batchRepo
                .findByPidBranchAndPidProductAndBatchNo(branchId, productId, batch)
                .orElseThrow(() -> new BusinessException(
                        "Batch '" + batch + "' does not exist in this branch, so stock cannot be "
                        + "taken out of it.",
                        HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND"));

        // Checked and deducted in one statement. Checking first and writing
        // afterwards let two documents pass the same check and both write the
        // balance they had each computed, which took the lot below what it
        // held; the database does the arithmetic now, and refuses the second.
        PosStockBatch saved = takeFromLot(lot, qty, () -> {
            PosProductInfo product = loadProduct(productId);
            return new BusinessException(
                    "Batch '" + batch + "' of '" + product.getProductName() + "' now holds "
                    + plain(lot.getQtyPcs()) + " pcs, which is less than the " + plain(qty)
                    + " pcs this document takes out of it. Someone else has taken the stock in "
                    + "the meantime — check the batch and try again.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BATCH_STOCK");
        });

        writeMovement(saved, movementType, PosStockMovement.OUT, qty, saved.getCostPerPcs(),
                refType, refMst, refDtl,
                movementDate != null ? movementDate : LocalDate.now(), remarks);

        log.debug("Stock OUT branch={} product={} batch={} -{} pcs (balance {})",
                branchId, productId, batch, qty, saved.getQtyPcs());
        return saved.getQtyPcs();
    }

    /**
     * Debits one lot, or refuses.
     *
     * <p>The check and the subtraction are the same statement, so two documents
     * cannot both be told the pieces are there. When the lot no longer holds
     * them the caller's own exception is raised — after refreshing the entity,
     * so the message quotes the balance that is actually on the shelf rather
     * than the one this transaction read a moment earlier.
     *
     * @return the lot, refreshed to what the database now holds
     */
    private PosStockBatch takeFromLot(PosStockBatch lot, BigDecimal qty,
                                      java.util.function.Supplier<? extends RuntimeException> onShort) {
        int taken = batchRepo.deductIfAvailable(lot.getPidStock(), qty);
        em.refresh(lot);
        if (taken == 0) throw onShort.get();
        return lot;
    }

    @Override
    @Transactional
    public BigDecimal returnToBatch(Long branchId, Long productId, String batchNo,
                                    BigDecimal qtyPcs, String refType, Long refMst, Long refDtl,
                                    String remarks) {
        requireBranch(branchId);
        BigDecimal qty = requirePositive(qtyPcs, "return quantity");

        PosStockBatch lot = batchRepo
                .findByPidBranchAndPidProductAndBatchNo(branchId, productId, requireBatchNo(batchNo))
                .orElseThrow(() -> new BusinessException(
                        "Batch '" + batchNo + "' does not exist in this branch, so stock cannot be "
                        + "returned into it. Returns must name the batch they came from.",
                        HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND"));

        lot.setQtyPcs(nvl(lot.getQtyPcs()).add(qty));
        if (PosStockBatch.EXHAUSTED.equals(lot.getStatus())) lot.setStatus(PosStockBatch.ACTIVE);
        PosStockBatch saved = batchRepo.save(lot);

        writeMovement(saved, PosStockMovement.SALES_RETURN, PosStockMovement.IN, qty,
                saved.getCostPerPcs(), refType, refMst, refDtl, LocalDate.now(), remarks);

        return saved.getQtyPcs();
    }

    @Override
    @Transactional
    public void reverseByReference(String refType, Long refMst, String movementType, String remarks) {
        List<PosStockMovement> originals =
                movementRepo.findByRefTypeAndRefMstAndMovementType(refType, refMst, movementType);

        if (originals.isEmpty()) {
            log.debug("Nothing to reverse for {} {} / {}", refType, refMst, movementType);
            return;
        }

        // Reverse the net position per lot, not every movement ever recorded.
        //
        // This used to walk the list and reverse each row, which is correct
        // exactly once. Editing a sales invoice reverses its allocation and
        // posts a fresh one, so the second edit found TWO sale rows and undid
        // both — returning stock the invoice had never taken. Every subsequent
        // edit made it worse, until CK_STOCK_ISSUED refused a negative issued
        // count and the whole edit failed. Nobody could have read that error
        // and known the previous edits had already inflated the shelf.
        //
        // The net is what the document actually holds right now: everything it
        // moved, less everything already given back. Reversing that is right on
        // the first edit and on the fiftieth, and it self-corrects a document
        // whose history is already uneven.
        Map<Long, BigDecimal> netByLot = new LinkedHashMap<>();
        Map<Long, PosStockMovement> sampleByLot = new LinkedHashMap<>();

        for (PosStockMovement m : originals) {
            boolean in = PosStockMovement.IN.equals(m.getDirection());
            netByLot.merge(m.getPidStock(), in ? nvl(m.getQtyPcs()) : nvl(m.getQtyPcs()).negate(),
                           BigDecimal::add);
            sampleByLot.putIfAbsent(m.getPidStock(), m);
        }

        // What has already been handed back for this document, per lot.
        for (PosStockMovement m : movementRepo.findByRefTypeAndRefMstAndMovementType(
                refType, refMst, PosStockMovement.ADJUST)) {
            boolean in = PosStockMovement.IN.equals(m.getDirection());
            netByLot.merge(m.getPidStock(), in ? nvl(m.getQtyPcs()) : nvl(m.getQtyPcs()).negate(),
                           BigDecimal::add);
        }

        int reversed = 0;
        for (Map.Entry<Long, BigDecimal> e : netByLot.entrySet()) {
            BigDecimal net = e.getValue();
            if (net.compareTo(BigDecimal.ZERO) == 0) continue;   // already square

            PosStockBatch lot = batchRepo.findById(e.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Stock batch", e.getKey()));
            PosStockMovement sample = sampleByLot.get(e.getKey());

            boolean wasIn = net.compareTo(BigDecimal.ZERO) > 0;
            BigDecimal qty = net.abs();

            if (wasIn) {
                if (nvl(lot.getQtyPcs()).compareTo(qty) < 0) {
                    throw new BusinessException(
                            "Batch '" + lot.getBatchNo() + "' holds " + plain(lot.getQtyPcs())
                            + " pcs but the movement being reversed brought in " + plain(qty)
                            + " pcs. Part of it has already been sold, so the document cannot be "
                            + "cancelled — raise a purchase return instead.",
                            HttpStatus.CONFLICT, "STOCK_ALREADY_CONSUMED");
                }
                lot.setQtyPcs(lot.getQtyPcs().subtract(qty));
                lot.setReceivedPcs(nvl(lot.getReceivedPcs()).subtract(qty));
            } else {
                lot.setQtyPcs(nvl(lot.getQtyPcs()).add(qty));
                lot.setIssuedPcs(nvl(lot.getIssuedPcs()).subtract(qty));
                if (PosStockBatch.EXHAUSTED.equals(lot.getStatus())) lot.setStatus(PosStockBatch.ACTIVE);
            }

            PosStockBatch saved = batchRepo.save(lot);

            // The reversal is a new row, not a deletion. The fact that a
            // correction happened is itself part of the audit trail.
            writeMovement(saved, PosStockMovement.ADJUST,
                    wasIn ? PosStockMovement.OUT : PosStockMovement.IN,
                    qty, sample != null ? sample.getCostPerPcs() : saved.getCostPerPcs(),
                    refType, refMst, null, LocalDate.now(),
                    remarks != null ? remarks : "Reversal of " + refType + " " + refMst);
            reversed++;
        }

        log.info("Reversed the net of {} lot(s) for {} {}", reversed, refType, refMst);
    }

    /* ══════════════════════════════════════════════════════════════════════
       READS
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public BigDecimal availablePcs(Long branchId, Long productId) {
        requireBranch(branchId);
        return nvl(batchRepo.availablePcs(branchId, productId, LocalDate.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal availableWholeUnits(Long branchId, Long productId, String unit) {
        requireBranch(branchId);
        PosProductInfo product = loadProduct(productId);
        Packing packing = packingFor(product);
        int unitSize = unitSizeOf(StockUnitConverter.normalise(unit), packing);

        BigDecimal size = BigDecimal.valueOf(unitSize);
        return batchRepo.findAllocatableBatches(branchId, productId, LocalDate.now(), false).stream()
                .map(b -> nvl(b.getQtyPcs()).divideToIntegralValue(size))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertAvailable(Long branchId, Long productId, BigDecimal qty, String unit) {
        PosProductInfo product = loadProduct(productId);
        String u = StockUnitConverter.normalise(unit);
        Packing packing = packingFor(product);

        BigDecimal requestedPcs = toPcs(qty, u, packing, product.getProductName());

        // Counted in whole units, lot by lot — not as a bare piece total.
        // Two lots of 11 pieces hold 22 pieces and zero boxes of twelve; summing
        // the pieces first would sell a box that cannot be assembled.
        int unitSize = unitSizeOf(u, packing);
        BigDecimal availablePcs = unitSize == 1
                ? availablePcs(branchId, productId)
                : walkBatches(batchRepo.findAllocatableBatches(branchId, productId, LocalDate.now(), false),
                              requestedPcs, unitSize).allocated();

        if (availablePcs.compareTo(requestedPcs) < 0) {
            throw new InsufficientStockException(
                    product.getProductName(), branchLabel(branchId), u,
                    qty,
                    StockUnitConverter.fromPcs(availablePcs, u, packing.pcsPerBox(), packing.pcsPerStrip()),
                    requestedPcs, availablePcs);
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /** One lot's share of a request. */
    private record Take(PosStockBatch lot, BigDecimal qty) {}

    /** The result of walking the lots: what would be taken, and what is missing. */
    private record Walk(List<Take> takes, BigDecimal allocated, BigDecimal shortfall) {}

    /**
     * Batch-wise FEFO walk.
     *
     * <p>The repository has already ordered the lots — earliest expiry, then
     * earliest receipt, then batch number. This only decides how much comes out
     * of each, taking whole lots until the last one, which is partial.
     */
    private Walk walkBatches(List<PosStockBatch> lots, BigDecimal requestedPcs) {
        return walkBatches(lots, requestedPcs, 1);
    }

    /**
     * Batch-wise FEFO walk, allocating in whole units of {@code pcsPerUnit}.
     *
     * <p>A lot only contributes as many <em>whole</em> units as it physically
     * holds. Selling five boxes of twelve from a lot of 55 pieces takes four
     * boxes (48 pieces) and leaves seven loose; the fifth box comes from the
     * next lot. The seven stay available for piece sales.
     *
     * <p>Allocating the raw pieces instead — 55 of the 60 from the first lot —
     * produces a line of "4.58 BOX", which is not a thing anyone can hand over
     * a counter, and leaves a rounding crumb behind in the lot because
     * {@code round(55/12, 4) × 12} is 54.9996, not 55.
     *
     * <p>{@code pcsPerUnit = 1} degenerates to a plain piece-level walk, which
     * is exactly what a PCS sale wants.
     */
    private Walk walkBatches(List<PosStockBatch> lots, BigDecimal requestedPcs, int pcsPerUnit) {
        List<Take> takes = new ArrayList<>();
        BigDecimal remaining = requestedPcs;
        BigDecimal unitSize = BigDecimal.valueOf(Math.max(pcsPerUnit, 1));

        for (PosStockBatch lot : lots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal available = nvl(lot.getQtyPcs());
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;

            // Whole units only. divideToIntegralValue floors, which is the
            // point: a lot cannot contribute part of a box.
            BigDecimal wholeUnitPcs = available.divideToIntegralValue(unitSize).multiply(unitSize);
            if (wholeUnitPcs.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal take = remaining.min(wholeUnitPcs);
            takes.add(new Take(lot, take));
            remaining = remaining.subtract(take);
        }

        return new Walk(takes, requestedPcs.subtract(remaining), remaining);
    }

    private StockAllocation buildAllocation(Long branchId, PosProductInfo product, Packing packing,
                                            BigDecimal requestedQty, String unit,
                                            BigDecimal requestedPcs, Walk walk) {
        List<AllocationLine> lines = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (Take take : walk.takes()) {
            PosStockBatch lot = take.lot();
            BigDecimal cost = nvl(lot.getCostPerPcs());
            BigDecimal value = take.qty().multiply(cost);
            totalCost = totalCost.add(value);

            lines.add(AllocationLine.builder()
                    .stockId(lot.getPidStock())
                    .batchNo(lot.getBatchNo())
                    .expiryDate(lot.getExpiryDate())
                    .availablePcs(lot.getQtyPcs())
                    .allocatedPcs(take.qty())
                    .costPerPcs(lot.getCostPerPcs())
                    .costValue(value)
                    .build());
        }

        return StockAllocation.builder()
                .branchId(branchId)
                .productId(product.getPidProduct())
                .productName(product.getProductName())
                .requestedQty(requestedQty)
                .requestedUnit(unit)
                .requestedPcs(requestedPcs)
                .allocatedPcs(walk.allocated())
                .shortfallPcs(walk.shortfall())
                .fullyAllocated(walk.shortfall().compareTo(BigDecimal.ZERO) == 0)
                .totalCost(totalCost)
                .lines(lines)
                .build();
    }

    /**
     * Rebuilds the allocation a line already performed, from its movement rows.
     *
     * <p>Recomputing it against today's stock would describe lots the sale never
     * touched — the lots it did touch may since have been sold out. The log is
     * the only honest answer to "what did this line consume".
     */
    private StockAllocation alreadyPosted(IssueCommand cmd, PosProductInfo product,
                                          String unit, BigDecimal requestedPcs) {
        String movementType = cmd.movementType() != null ? cmd.movementType() : PosStockMovement.SALE;
        List<PosStockMovement> posted = movementRepo
                .findByRefTypeAndRefDtlAndMovementTypeOrderByPidMovementAsc(
                        cmd.refType(), cmd.refDtl(), movementType);

        List<AllocationLine> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (PosStockMovement m : posted) {
            BigDecimal cost = nvl(m.getCostPerPcs());
            BigDecimal value = m.getQtyPcs().multiply(cost);
            allocated = allocated.add(m.getQtyPcs());
            totalCost = totalCost.add(value);

            lines.add(AllocationLine.builder()
                    .stockId(m.getPidStock())
                    .batchNo(m.getBatchNo())
                    .allocatedPcs(m.getQtyPcs())
                    .availablePcs(m.getBalanceAfter())
                    .costPerPcs(m.getCostPerPcs())
                    .costValue(value)
                    .build());
        }

        return StockAllocation.builder()
                .branchId(cmd.branchId())
                .productId(product.getPidProduct())
                .productName(product.getProductName())
                .requestedQty(cmd.qty())
                .requestedUnit(unit)
                .requestedPcs(requestedPcs)
                .allocatedPcs(allocated)
                .shortfallPcs(BigDecimal.ZERO)
                .fullyAllocated(true)
                .totalCost(totalCost)
                .lines(lines)
                .build();
    }

    private void writeMovement(PosStockBatch lot, String type, String direction, BigDecimal qty,
                               BigDecimal costPerPcs, String refType, Long refMst, Long refDtl,
                               LocalDate when, String remarks) {
        PosStockMovement m = new PosStockMovement();
        m.setPidStock(lot.getPidStock());
        m.setPidOrg(lot.getPidOrg());
        m.setPidBranch(lot.getPidBranch());
        m.setPidProduct(lot.getPidProduct());
        m.setBatchNo(lot.getBatchNo());
        m.setMovementType(type);
        m.setDirection(direction);
        m.setQtyPcs(qty);
        m.setBalanceAfter(lot.getQtyPcs());
        m.setCostPerPcs(costPerPcs);
        m.setRefType(refType);
        m.setRefMst(refMst);
        m.setRefDtl(refDtl);
        m.setMovementDate(when);
        m.setRemarks(remarks);
        movementRepo.save(m);
    }

    /**
     * Weighted average of the cost already in the lot and the cost arriving.
     * Falls back to whichever side is known when the other is not.
     */
    private BigDecimal blendCost(PosStockBatch lot, BigDecimal incomingQty, BigDecimal incomingCost) {
        BigDecimal existingQty  = nvl(lot.getQtyPcs());
        BigDecimal existingCost = lot.getCostPerPcs();

        if (incomingCost == null) return existingCost;
        if (existingCost == null || existingQty.compareTo(BigDecimal.ZERO) <= 0) return incomingCost;

        BigDecimal totalQty = existingQty.add(incomingQty);
        if (totalQty.compareTo(BigDecimal.ZERO) == 0) return incomingCost;

        return existingCost.multiply(existingQty)
                .add(incomingCost.multiply(incomingQty))
                .divide(totalQty, 6, java.math.RoundingMode.HALF_UP);
    }

    /** Pieces per box and per strip for a product, from Box → Leaf. */
    private record Packing(Integer pcsPerBox, Integer pcsPerStrip) {}

    private Packing packingFor(PosProductInfo product) {
        Integer pcsPerStrip = null;
        Integer stripsPerBox = null;

        if (product.getPidBox() != null) {
            PosBoxInfo box = boxRepo.findById(product.getPidBox()).orElse(null);
            if (box != null) {
                stripsPerBox = box.getNoOfLeaf();
                if (box.getPidLeaf() != null) {
                    PosLeafInfo leaf = leafRepo.findById(box.getPidLeaf()).orElse(null);
                    if (leaf != null) pcsPerStrip = leaf.getNoOfPcs();
                }
            }
        }

        Integer pcsPerBox = (stripsPerBox != null && pcsPerStrip != null)
                ? stripsPerBox * pcsPerStrip : null;
        return new Packing(pcsPerBox, pcsPerStrip);
    }

    /**
     * Pieces in one unit, for whole-unit allocation. Falls back to 1, which
     * makes the walk piece-level — right for PCS, and the safe default for a
     * product whose packing is not configured.
     */
    private int unitSizeOf(String unit, Packing packing) {
        Integer factor = StockUnitConverter.factorFor(unit, packing.pcsPerBox(), packing.pcsPerStrip());
        return factor != null && factor > 0 ? factor : 1;
    }

    private BigDecimal toPcs(BigDecimal qty, String unit, Packing packing, String productName) {
        if (!StockUnitConverter.isKnownUnit(unit)) {
            throw new BusinessException(
                    "'" + unit + "' is not a stock unit. Use BOX, STRIP or PCS.",
                    HttpStatus.BAD_REQUEST, "UNKNOWN_STOCK_UNIT");
        }
        try {
            return StockUnitConverter.toPcs(qty, unit, packing.pcsPerBox(), packing.pcsPerStrip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "'" + productName + "' has no " + unit.toLowerCase() + " packing configured, so it "
                    + "cannot be transacted in that unit. Set its box and strip sizes in Product Master.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "PACKING_NOT_CONFIGURED");
        }
    }

    private PosProductInfo loadProduct(Long productId) {
        return productRepo.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private static void requireBranch(Long branchId) {
        if (branchId == null) {
            throw new BusinessException(
                    "Stock movements must name a branch. Stock that is not in a branch is not stock.",
                    HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }
    }

    private static BigDecimal requirePositive(BigDecimal v, String what) {
        if (v == null || v.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(
                    "The " + what + " must be greater than zero.",
                    HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        }
        return v;
    }

    private static String requireBatchNo(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            throw new BusinessException(
                    "A stock movement must name its batch. Without one the pieces cannot be traced "
                    + "to a cost or an expiry.",
                    HttpStatus.BAD_REQUEST, "BATCH_REQUIRED");
        }
        return batchNo.trim();
    }

    /** Branch name is cosmetic here; the id is enough when it is unavailable. */
    private String branchLabel(Long branchId) {
        return branchId == null ? null : "branch " + branchId;
    }

    private static BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
