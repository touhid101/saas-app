package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.StockResponses.AllocationLine;
import com.friendspharma.app.dto.response.StockResponses.StockAllocation;
import com.friendspharma.app.entity.PosBoxInfo;
import com.friendspharma.app.entity.PosLeafInfo;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.entity.PosStockMovement;
import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.interfaces.StockPostingService;
import com.friendspharma.app.service.interfaces.StockService;
import com.friendspharma.app.util.StockUnitConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps approved documents onto stock movements.
 *
 * <p>The direction table is the whole point of this class existing:
 *
 * <pre>
 *   PU  IN   named batch      SL  OUT  FEFO across lots
 *   PR  OUT  named batch      SR  IN   named batch (the one the sale took)
 * </pre>
 *
 * <p>Quantity on a line is in whatever unit it was written in — BOX, STRIP or
 * PCS — and {@code NO_OF_PCS} on that same line is the conversion factor frozen
 * at entry. Pieces are {@code quantity × noOfPcs}, and that one expression is
 * correct for every type and every unit. The legacy view instead assumed
 * purchases were always in boxes and sales always in pieces, which was only true
 * until someone bought a strip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPostingServiceImpl implements StockPostingService {

    private static final String REF_TYPE = "TRANSACTION";

    private final StockService                 stockService;
    private final PosTransactionMstRepository  mstRepo;
    private final PosTransactionDtlRepository  dtlRepo;
    private final PosProductInfoRepository     productRepo;
    private final PosBoxInfoRepository         boxRepo;
    private final PosLeafInfoRepository        leafRepo;
    /** Reads back the cost a sale left at, so a return restores it rather than the refund. */
    private final PosStockMovementRepository   movementRepo;

    /* ══════════════════════════════════════════════════════════════════════
       POST
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public List<PostingResult> post(Long tranMstId) {
        PosTransactionMst mst = mstRepo.findById(tranMstId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", tranMstId));

        String type = normaliseType(mst.getTransactionType());
        Long branchId = requireBranch(mst);
        Long orgId = mst.getPidOrg();
        LocalDate when = mst.getTransactionDate() != null ? mst.getTransactionDate() : LocalDate.now();

        List<PosTransactionDtl> lines = dtlRepo.findByPidTranMst(tranMstId);
        if (lines.isEmpty()) {
            throw new BusinessException(
                    "Transaction " + mst.getInvoiceNo() + " has no lines, so there is nothing to post to stock.",
                    HttpStatus.BAD_REQUEST, "TRANSACTION_EMPTY");
        }

        /* [V69] Built once for the invoice, not once per line: a gift's share
           depends on every other line's worth, so the question can only be
           answered with all of them in hand. Identity for an invoice with no
           gift, which is nearly all of them. */
        CostAllocation allocation = CostAllocation.of(mst, lines);

        List<PostingResult> results = new ArrayList<>(lines.size());
        for (PosTransactionDtl line : lines) {
            results.add(switch (type) {
                case "PU" -> receiveLine(mst, line, orgId, branchId, when,
                                         PosStockMovement.PURCHASE, allocation);
                case "SR" -> receiveLine(mst, line, orgId, branchId, when,
                                         PosStockMovement.SALES_RETURN, allocation);
                case "SL" -> issueLine(mst, line, orgId, branchId, when,
                                       PosStockMovement.SALE);
                case "PR" -> issueNamedBatch(mst, line, orgId, branchId, when,
                                             PosStockMovement.PURCHASE_RETURN);
                default -> throw new BusinessException(
                        "Transaction type '" + mst.getTransactionType() + "' does not move stock. "
                        + "Expected PU, PR, SL or SR.",
                        HttpStatus.BAD_REQUEST, "UNKNOWN_TRANSACTION_TYPE");
            });
        }

        log.info("Posted {} ({}) to stock: {} line(s), branch {}",
                mst.getInvoiceNo(), type, results.size(), branchId);
        return results;
    }

    /* ── PU, SR — inbound, into the batch the line names ──────────────────── */

    private PostingResult receiveLine(PosTransactionMst mst, PosTransactionDtl line,
                                      Long orgId, Long branchId, LocalDate when,
                                      String movementType, CostAllocation allocation) {
        PosProductInfo product = loadProduct(line.getPidProduct());
        /* [V67] Everything that arrived, bonus included. The shelf gets the free
           units — they are physically there — and because the cost below is the
           line's money divided by this figure, the bonus lowers the cost of every
           piece in the batch rather than appearing as stock that came from
           nowhere. */
        BigDecimal qtyPcs = receivedPcs(line);
        String batchNo = requireBatchNo(line, mst);
        Packing packing = packingFor(product);
        BigDecimal costPerPcs = unitCost(mst, line, qtyPcs, allocation);

        requireCostBelowMrp(product, line, packing, costPerPcs, qtyPcs);

        stockService.receive(new StockService.ReceiveCommand(
                orgId, branchId, product.getPidProduct(),
                batchNo,
                line.getExpiryDate(),
                qtyPcs,
                costPerPcs,
                line.getMrpPrice(),
                packing.pcsPerBox(), packing.pcsPerStrip(),
                when,
                movementType,
                REF_TYPE, mst.getPidTranMst(), line.getPidTranDtl(),
                mst.getInvoiceNo()));

        return new PostingResult(line.getPidTranDtl(), product.getPidProduct(), batchNo,
                movementType, PosStockMovement.IN, qtyPcs, null);
    }

    /* ── SL — outbound, batch-wise FEFO ───────────────────────────────────── */

    private PostingResult issueLine(PosTransactionMst mst, PosTransactionDtl line,
                                    Long orgId, Long branchId, LocalDate when,
                                    String movementType) {
        BigDecimal qtyPcs = toPcs(line);

        // A line that already names its batch is honoured, not re-allocated.
        //
        // Two flows reach here. A sales invoice is built from an allocation
        // preview and its lines are already batch-specific — pharmacy invoices
        // carry batch numbers, and re-running FEFO at posting time could pick
        // different lots than the ones printed. A quick POS sale names no batch,
        // and FEFO chooses for it.
        if (line.getBatchNo() != null && !line.getBatchNo().isBlank()) {
            stockService.issueFromBatch(orgId, branchId, line.getPidProduct(),
                    line.getBatchNo().trim(), qtyPcs,
                    movementType, REF_TYPE, mst.getPidTranMst(), line.getPidTranDtl(),
                    when, mst.getInvoiceNo());

            return new PostingResult(line.getPidTranDtl(), line.getPidProduct(), line.getBatchNo(),
                    movementType, PosStockMovement.OUT, qtyPcs, null);
        }

        // Allocation is driven in pieces, not in the line's unit: the line
        // already carries its own conversion factor, and re-deriving one from
        // current master data could disagree with what was quoted.
        StockAllocation allocation = stockService.issue(new StockService.IssueCommand(
                orgId, branchId, line.getPidProduct(),
                qtyPcs, StockUnitConverter.PCS,
                movementType,
                REF_TYPE, mst.getPidTranMst(), line.getPidTranDtl(),
                when,
                mst.getInvoiceNo()));

        // Record which lot the sale actually took. Without this a sales return
        // has no lot to go back to, and the returned pieces would either invent
        // a new batch or land on the wrong cost.
        stampAllocatedBatch(line, allocation);

        return new PostingResult(line.getPidTranDtl(), line.getPidProduct(), line.getBatchNo(),
                movementType, PosStockMovement.OUT, qtyPcs, allocation);
    }

    /**
     * Writes the consumed lot back onto the sale line.
     *
     * <p>A sale that spans two lots has no single batch. The line keeps the
     * first lot consumed and the expiry that governs it, because that is the
     * one a return will be measured against; the full split stays in
     * {@code POS_STOCK_MOVEMENT}, which is where a multi-lot sale is properly
     * represented.
     */
    private void stampAllocatedBatch(PosTransactionDtl line, StockAllocation allocation) {
        if (allocation == null || allocation.getLines() == null || allocation.getLines().isEmpty()) return;

        AllocationLine first = allocation.getLines().get(0);
        line.setBatchNo(first.getBatchNo());
        if (first.getExpiryDate() != null) line.setExpiryDate(first.getExpiryDate());
        dtlRepo.save(line);

        if (allocation.getLines().size() > 1) {
            log.debug("Sale line {} spans {} batches; line stamped with {}, full split in the movement log",
                    line.getPidTranDtl(), allocation.getLines().size(), first.getBatchNo());
        }
    }

    /* ── PR — outbound, from the batch the line names ─────────────────────── */

    /**
     * A purchase return goes back to the supplier out of the specific lot that
     * was delivered, never by FEFO. Returning "whatever expires soonest" would
     * send back goods the supplier never sent.
     */
    private PostingResult issueNamedBatch(PosTransactionMst mst, PosTransactionDtl line,
                                          Long orgId, Long branchId, LocalDate when,
                                          String movementType) {
        BigDecimal qtyPcs = toPcs(line);
        String batchNo = requireBatchNo(line, mst);

        stockService.issueFromBatch(orgId, branchId, line.getPidProduct(), batchNo, qtyPcs,
                movementType, REF_TYPE, mst.getPidTranMst(), line.getPidTranDtl(),
                when, mst.getInvoiceNo());

        return new PostingResult(line.getPidTranDtl(), line.getPidProduct(), batchNo,
                movementType, PosStockMovement.OUT, qtyPcs, null);
    }

    /* ══════════════════════════════════════════════════════════════════════
       REVERSE
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public void reverse(Long tranMstId, String remarks) {
        PosTransactionMst mst = mstRepo.findById(tranMstId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", tranMstId));

        String movementType = switch (normaliseType(mst.getTransactionType())) {
            case "PU" -> PosStockMovement.PURCHASE;
            case "PR" -> PosStockMovement.PURCHASE_RETURN;
            case "SL" -> PosStockMovement.SALE;
            case "SR" -> PosStockMovement.SALES_RETURN;
            default -> throw new BusinessException(
                    "Transaction type '" + mst.getTransactionType() + "' never moved stock.",
                    HttpStatus.BAD_REQUEST, "UNKNOWN_TRANSACTION_TYPE");
        };

        stockService.reverseByReference(REF_TYPE, tranMstId, movementType,
                remarks != null ? remarks : "Cancellation of " + mst.getInvoiceNo());
    }

    /* ══════════════════════════════════════════════════════════════════════
       LINE ARITHMETIC
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Pieces on a line: {@code quantity × noOfPcs}.
     *
     * <p>{@code noOfPcs} is the factor for the unit the line was written in,
     * frozen when the line was entered. Reading the factor live from the
     * product instead would let a later pack-size edit silently re-value stock
     * that was received under the old packing.
     */
    private BigDecimal toPcs(PosTransactionDtl line) {
        if (line.getQuantity() == null) {
            throw new BusinessException(
                    "Line " + line.getPidTranDtl() + " has no quantity.",
                    HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        }
        Integer factor = line.getNoOfPcs();
        if (factor == null || factor <= 0) {
            // V9 backfilled and made the column NOT NULL, so this is a coding
            // fault on a new write path rather than legacy data.
            throw new BusinessException(
                    "Line " + line.getPidTranDtl() + " has no pieces-per-unit factor, so its quantity "
                    + "cannot be converted to pieces. This line was written without one.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_UNIT_FACTOR");
        }
        return line.getQuantity().multiply(BigDecimal.valueOf(factor));
    }

    /**
     * [V67] What actually arrived: purchased pieces plus the free ones.
     *
     * <p>Deliberately <em>not</em> folded into {@link #toPcs}, which three call
     * sites share — two of them outbound. A bonus belongs to goods coming in; a
     * sale that somehow carried one would have issued more than it sold, and the
     * shared helper would have made that silent. Keeping it here means the only
     * path that can add free units is the one that receives them.
     *
     * <p>This is also the divisor in {@link #unitCost}, so the effective cost per
     * piece — net line total over everything received — falls out of the same
     * number rather than being computed twice and left to drift.
     */
    private BigDecimal receivedPcs(PosTransactionDtl line) {
        BigDecimal purchased = toPcs(line);
        BigDecimal bonus = line.getBonusQty();
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) return purchased;

        int factor = line.getNoOfPcs();   // toPcs has already refused a bad one
        return purchased.add(bonus.multiply(BigDecimal.valueOf(factor)));
    }

    /** Landed cost of one piece: the line's total spread over the pieces it brought in. */
    /**
     * Landed cost of one piece, <b>net of the invoice-level discount</b>.
     *
     * <p>The line's own total already carries the trade price, IGTA and any line
     * discount. A header discount is applied to the invoice as a whole, so each
     * line bears its proportional share — a 2% invoice discount makes every
     * piece on it 2% cheaper to hold.
     *
     * <p>Dividing the raw line total instead values stock above what was paid
     * for it, and understates every margin measured against it by the same
     * percentage. The legacy {@code VW_PRODUCT_STOCK} apportioned it too:
     * {@code (TOTAL_PRICE − TOTAL_PRICE × INVOICE_DIS_PCT/100) / QUANTITY}.
     */
    /**
     * What one piece on this line cost.
     *
     * <p>For a purchase the line total is money paid, so dividing it by pieces
     * gives the landed cost. For a <em>sales return</em> the line total is the
     * refund — the price the customer paid — and using it books returned goods
     * back onto the shelf valued at their selling price.
     *
     * <p>That is not a rounding difference. It rewrites the batch's weighted
     * average cost, so every later sale from that lot reports the wrong COGS,
     * and stock valuation counts margin as inventory. Returned goods cost what
     * they cost when they left; the return does not change that.
     */
    private BigDecimal unitCost(PosTransactionMst mst, PosTransactionDtl line, BigDecimal qtyPcs,
                                CostAllocation allocation) {
        if (qtyPcs.compareTo(BigDecimal.ZERO) <= 0) return null;

        if ("SR".equals(mst.getTransactionType())) {
            BigDecimal original = costOfOriginalSale(line);
            // Falling back to the refund would reintroduce exactly the bug this
            // guards against, so an unlinked return is refused instead.
            if (original == null) {
                throw new BusinessException(
                        "This return line does not point at the sale line it reverses, so the cost "
                        + "the goods left at cannot be recovered. Raise the return from the invoice.",
                        HttpStatus.CONFLICT, "RETURN_COST_UNKNOWN");
            }
            return original;
        }

        /* [V69] The allocated share, which is the line's own net cost unless a
           gift on this invoice made it everybody's business. A gift line has no
           totalPrice of its own, so it is the allocation or nothing. */
        BigDecimal net = allocation.netFor(mst, line);
        if (net == null) return null;
        return net.divide(qtyPcs, 6, RoundingMode.HALF_UP);
    }

    /**
     * Refuses a receipt whose landed cost per piece exceeds MRP per piece.
     *
     * <p>MRP is the maximum a pharmacy may legally charge, so buying above it
     * guarantees a loss on every piece. In practice it never means that: it means
     * the unit and the price disagree — a box price entered against a strip or a
     * piece, which multiplies the per-piece cost by the packing factor.
     *
     * <p>Caught here because nothing downstream can. The cost is frozen onto the
     * lot, every later sale reports COGS from it, and the error surfaces weeks
     * later as a margin nobody can explain. Invoice PU260800001 bought 4 "strips"
     * of Napa at 665.33 each against a 75.60 strip MRP — the line was 4 boxes —
     * and it alone drove a day's profit to −2,096.
     *
     * <p>Skipped where the product carries no MRP or no packing: there is nothing
     * to compare against, and refusing on missing reference data would block
     * receipts that are perfectly fine.
     */
    private void requireCostBelowMrp(PosProductInfo product, PosTransactionDtl line,
                                     Packing packing, BigDecimal costPerPcs, BigDecimal qtyPcs) {
        if (costPerPcs == null || costPerPcs.compareTo(BigDecimal.ZERO) <= 0) return;

        // Both figures have to reach a per-piece basis before they can be
        // compared, and the two MRPs available are quoted differently: the one
        // on the line is in the unit the goods were bought in, the one on the
        // product master is always per box. Dividing the line's MRP by the box
        // factor — as this once did — turned a correctly priced PCS line into a
        // twelvefold breach and refused a receipt that was perfectly good.
        // Two MRPs are available and they are quoted on different bases: the one
        // on the line is per purchase unit, the one on the product master is
        // always per box. Either alone is unsafe. Trusting the line lets a row
        // written before the pricing fix — a box MRP sitting on a PCS line —
        // present a ceiling twelve times too high and wave the bad cost
        // through. Trusting only the master ignores a genuinely different MRP
        // negotiated on the invoice.
        //
        // So take the lower. Being too strict refuses a receipt somebody can
        // look at; being too lax puts stock on the shelf costing more than it
        // can be sold for, and that only surfaces later as negative margin.
        BigDecimal mrpPerPcs = null;

        if (line.getMrpPrice() != null && line.getMrpPrice().compareTo(BigDecimal.ZERO) > 0) {
            int pcsPerUnit = (line.getNoOfPcs() != null && line.getNoOfPcs() > 0) ? line.getNoOfPcs() : 1;
            mrpPerPcs = line.getMrpPrice().divide(BigDecimal.valueOf(pcsPerUnit), 6, RoundingMode.HALF_UP);
        }

        Integer pcsPerBox = packing.pcsPerBox();
        if (product.getMrpPrice() != null && product.getMrpPrice().compareTo(BigDecimal.ZERO) > 0
                && pcsPerBox != null && pcsPerBox > 0) {
            BigDecimal fromMaster = product.getMrpPrice()
                    .divide(BigDecimal.valueOf(pcsPerBox), 6, RoundingMode.HALF_UP);
            mrpPerPcs = (mrpPerPcs == null) ? fromMaster : mrpPerPcs.min(fromMaster);
        }

        if (mrpPerPcs == null || mrpPerPcs.compareTo(BigDecimal.ZERO) <= 0) return;
        if (costPerPcs.compareTo(mrpPerPcs) <= 0) return;

        BigDecimal factor = costPerPcs.divide(mrpPerPcs, 1, RoundingMode.HALF_UP);
        throw new BusinessException(
                product.getProductName() + ": this line works out at "
                + costPerPcs.setScale(2, RoundingMode.HALF_UP) + " per piece, which is "
                + factor + " times its MRP of " + mrpPerPcs.setScale(2, RoundingMode.HALF_UP)
                + " per piece. Stock cannot be bought above the price it may be sold at — "
                + "check whether the unit (" + (line.getSalesUnit() == null ? "?" : line.getSalesUnit())
                + ") matches the price entered. At this quantity the line would have to total "
                + mrpPerPcs.multiply(qtyPcs).setScale(2, RoundingMode.HALF_UP)
                + " or less.",
                HttpStatus.UNPROCESSABLE_ENTITY, "COST_ABOVE_MRP");
    }

    /** The cost recorded when these goods were issued, read off that movement. */
    private BigDecimal costOfOriginalSale(PosTransactionDtl returnLine) {
        if (returnLine.getPidTranDtlReturn() == null) return null;
        return movementRepo.findByRefTypeAndRefDtlAndMovementTypeOrderByPidMovementAsc(
                        REF_TYPE, returnLine.getPidTranDtlReturn(), PosStockMovement.SALE)
                .stream()
                .map(PosStockMovement::getCostPerPcs)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Line total less its share of the invoice discount. */
    static BigDecimal netLineTotal(PosTransactionMst mst, PosTransactionDtl line) {
        BigDecimal total = line.getTotalPrice() != null ? line.getTotalPrice() : BigDecimal.ZERO;
        if (mst == null) return total;

        BigDecimal pct = mst.getInvoiceDisPct() != null ? mst.getInvoiceDisPct() : BigDecimal.ZERO;
        if (pct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal share = total.multiply(pct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            total = total.subtract(share);
        }

        /* [V68] Cashback, allocated the same way and for the same reason.
         *
         * A header-level reduction has to reach the goods or the shelf is valued
         * at a price nobody paid. The discount above is quoted as a percentage,
         * so each line's share falls straight out of it; cashback is an amount,
         * so the share is this line's weight in the invoice — its total over the
         * invoice total. Both are allocation by line value; only the arithmetic
         * to get there differs.
         *
         * Guarded on a positive invoice total: a zero-value invoice has no
         * weights to divide by, and dividing by it would turn an incentive into
         * an ArithmeticException on approval.
         */
        BigDecimal cashback = mst.getCashbackAmount() != null
                ? mst.getCashbackAmount() : BigDecimal.ZERO;
        BigDecimal invoiceTotal = mst.getTotalAmount() != null
                ? mst.getTotalAmount() : BigDecimal.ZERO;

        if (cashback.compareTo(BigDecimal.ZERO) > 0
                && invoiceTotal.compareTo(BigDecimal.ZERO) > 0
                && line.getTotalPrice() != null) {

            BigDecimal weight = line.getTotalPrice()
                    .divide(invoiceTotal, 10, RoundingMode.HALF_UP);
            total = total.subtract(cashback.multiply(weight).setScale(2, RoundingMode.HALF_UP));
        }

        // Never below zero: a cashback that swallowed a line would put a negative
        // cost in the batch, and stock worth less than nothing is not a thing.
        return total.max(BigDecimal.ZERO);
    }

    /**
     * [V69] How an invoice's cost is spread across its lines.
     *
     * <p><b>Ordinarily it is not spread at all.</b> A line's cost is its own
     * money, less its share of the invoice discount and cashback — which is what
     * {@link #netLineTotal} already returns, and what every invoice without a
     * gift keeps getting. {@link #of} returns the identity allocation for those,
     * so the common case gains an object and changes nothing.
     *
     * <p><b>A gift changes the question.</b> A free product's line carries no
     * money, so costing it line-by-line values it at zero — and a product that
     * cost nothing reports infinite margin and drags every profitability report
     * it appears in with it. The honest treatment is that the invoice bought
     * everything on it together: its whole net cost belongs to all the goods
     * that arrived, apportioned by what each is worth on the shelf.
     *
     * <p>Selling value, not quantity: two bottles of oil and two hundred tablets
     * are not equal claims on the same cost. MRP is what the pharmacy will sell
     * them for, so it is the fairest measure of what each line is worth, and it
     * is already on the line as a price snapshot.
     */
    static final class CostAllocation {

        private final Map<Long, BigDecimal> netByLine;

        private CostAllocation(Map<Long, BigDecimal> netByLine) {
            this.netByLine = netByLine;
        }

        /** The net cost to attribute to one line. */
        BigDecimal netFor(PosTransactionMst mst, PosTransactionDtl line) {
            if (netByLine == null) return netLineTotal(mst, line);
            BigDecimal share = netByLine.get(line.getPidTranDtl());
            return share != null ? share : netLineTotal(mst, line);
        }

        static CostAllocation of(PosTransactionMst mst, List<PosTransactionDtl> lines) {
            boolean hasGift = lines.stream().anyMatch(CostAllocation::isGift);
            if (!hasGift) return new CostAllocation(null);

            // What the invoice really cost, after discount and cashback.
            BigDecimal invoiceNet = lines.stream()
                    .map(l -> netLineTotal(mst, l))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<Long, BigDecimal> value = new LinkedHashMap<>();
            BigDecimal totalValue = BigDecimal.ZERO;
            for (PosTransactionDtl l : lines) {
                BigDecimal v = sellingValue(l);
                value.put(l.getPidTranDtl(), v);
                totalValue = totalValue.add(v);
            }

            /* No MRP anywhere on the invoice, so there is no measure of worth to
               divide by. Falling back leaves the gift at zero rather than
               dividing by zero — a wrong-but-visible cost beats an exception on
               approval, and the cost-below-MRP guard has nothing to check
               against either. */
            if (totalValue.compareTo(BigDecimal.ZERO) <= 0) return new CostAllocation(null);

            Map<Long, BigDecimal> out = new LinkedHashMap<>();
            for (PosTransactionDtl l : lines) {
                BigDecimal weight = value.get(l.getPidTranDtl())
                        .divide(totalValue, 10, RoundingMode.HALF_UP);
                out.put(l.getPidTranDtl(),
                        invoiceNet.multiply(weight).setScale(2, RoundingMode.HALF_UP));
            }
            return new CostAllocation(out);
        }

        /** MRP × everything that arrived on this line, bonus included. */
        private static BigDecimal sellingValue(PosTransactionDtl l) {
            BigDecimal mrp = l.getMrpPrice() != null ? l.getMrpPrice() : BigDecimal.ZERO;
            BigDecimal qty = (l.getQuantity() != null ? l.getQuantity() : BigDecimal.ZERO)
                    .add(l.getBonusQty() != null ? l.getBonusQty() : BigDecimal.ZERO);
            return mrp.multiply(qty).max(BigDecimal.ZERO);
        }

        static boolean isGift(PosTransactionDtl l) {
            return "GIFT".equals(l.getLineKind());
        }
    }

    private String requireBatchNo(PosTransactionDtl line, PosTransactionMst mst) {
        String batchNo = line.getBatchNo();
        if (batchNo == null || batchNo.isBlank()) batchNo = mst.getBatchNo();
        if (batchNo == null || batchNo.isBlank()) {
            throw new BusinessException(
                    "Line " + line.getPidTranDtl() + " of " + mst.getInvoiceNo() + " names no batch. "
                    + "Stock cannot be traced to a cost or an expiry without one.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_REQUIRED");
        }
        return batchNo.trim();
    }

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

    private PosProductInfo loadProduct(Long productId) {
        return productRepo.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private static Long requireBranch(PosTransactionMst mst) {
        if (mst.getPidBranch() == null) {
            throw new BusinessException(
                    "Transaction " + mst.getInvoiceNo() + " has no branch, so its stock has nowhere to land.",
                    HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }
        return mst.getPidBranch();
    }

    private static String normaliseType(String type) {
        return type == null ? "" : type.trim().toUpperCase();
    }
}
