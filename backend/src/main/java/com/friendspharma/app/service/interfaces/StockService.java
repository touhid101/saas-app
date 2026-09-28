package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.response.StockResponses.StockAllocation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The single write path for stock.
 *
 * <p>Nothing else may change a balance. Every movement goes through here so it
 * lands in {@code POS_STOCK_BATCH} and {@code POS_STOCK_MOVEMENT} together, in
 * one transaction — a balance changed without a matching movement row is
 * exactly the drift this module exists to make impossible.
 *
 * <p>All quantities crossing this boundary are in <b>pieces</b> unless the
 * method takes an explicit unit. Branch is always required and never defaulted:
 * stock that is not in a branch is not stock.
 */
public interface StockService {

    /* ── Inbound ──────────────────────────────────────────────────────────── */

    /**
     * Records receipt of stock into a lot, creating the lot if this is its
     * first delivery and topping it up if it already exists.
     *
     * <p>Idempotent per {@code (refType, refDtl, movementType)}: approving the
     * same purchase line twice records one receipt. Re-approval used to double
     * the quantity, which is the kind of error nobody notices until a stock
     * count disagrees months later.
     *
     * @return the lot's balance after the receipt
     */
    BigDecimal receive(ReceiveCommand command);

    /* ── Outbound ─────────────────────────────────────────────────────────── */

    /**
     * Allocates and deducts stock, batch-wise, earliest expiry first.
     *
     * <p>The allocation walks whole <em>batches</em> — not expiry-date buckets,
     * which is what the legacy procedure did. Two lots sharing an expiry are
     * consumed one after the other and keep their own costs, so the movement
     * rows carry a real per-batch COGS.
     *
     * <p>Refuses the whole request when it cannot be filled: a partially
     * allocated sale leaves the invoice and the stock disagreeing, and there is
     * no good way to explain that to a customer at a counter.
     */
    StockAllocation issue(IssueCommand command);

    /**
     * What {@link #issue} would do, without doing it.
     *
     * <p>Walks the lots in the same order, so a preview shown to a user is the
     * allocation they will get. Takes no locks and writes nothing.
     */
    StockAllocation preview(Long branchId, Long productId, BigDecimal qty, String unit);

    /**
     * Takes stock out of one named lot, bypassing FEFO.
     *
     * <p>For purchase returns. Goods go back to the supplier out of the exact
     * delivery they arrived in — returning "whatever expires soonest" would send
     * back stock that supplier never sent, at a cost they never charged.
     *
     * @return the lot's balance after the withdrawal
     */
    BigDecimal issueFromBatch(Long orgId, Long branchId, Long productId, String batchNo,
                              BigDecimal qtyPcs, String movementType, String refType,
                              Long refMst, Long refDtl, LocalDate movementDate, String remarks);

    /**
     * Puts stock back — a sales return, or the reversal of a cancelled issue.
     * Returns land in the batch they came from, so the returned pieces keep
     * their original expiry and cost.
     */
    BigDecimal returnToBatch(Long branchId, Long productId, String batchNo,
                             BigDecimal qtyPcs, String refType, Long refMst, Long refDtl,
                             String remarks);

    /**
     * Reverses every movement previously posted against a reference.
     *
     * <p>Used when an approved document is cancelled. Writes opposing rows
     * rather than deleting the originals: the fact that a correction happened
     * is itself part of the audit trail.
     */
    void reverseByReference(String refType, Long refMst, String movementType, String remarks);

    /* ── Reads used by the write path ─────────────────────────────────────── */

    /** Sellable pieces of a product in a branch. Expired lots excluded. */
    BigDecimal availablePcs(Long branchId, Long productId);

    /**
     * How many whole {@code unit}s the branch can actually supply.
     *
     * <p>Counted lot by lot, not by dividing a piece total. Two lots of 11
     * pieces hold 22 pieces and <em>zero</em> boxes of twelve — a box cannot be
     * assembled from two different batches, so the piece total overstates what
     * is sellable.
     */
    BigDecimal availableWholeUnits(Long branchId, Long productId, String unit);

    /**
     * Throws {@code InsufficientStockException} when the branch cannot cover
     * {@code qty} of {@code unit}.
     *
     * <p>The shortfall is reported in the unit the caller asked for. Telling
     * someone who wants one box that five pieces are available is arithmetically
     * true and practically useless.
     */
    void assertAvailable(Long branchId, Long productId, BigDecimal qty, String unit);

    /* ── Commands ─────────────────────────────────────────────────────────── */

    /**
     * A delivery of one lot.
     *
     * @param qtyPcs      pieces received — already converted by the caller,
     *                    which owns the unit the document was written in
     * @param costPerPcs  landed cost of one piece, for COGS
     * @param pcsPerBox   packing snapshot, frozen against later master-data edits
     */
    record ReceiveCommand(
            Long       orgId,
            Long       branchId,
            Long       productId,
            String     batchNo,
            LocalDate  expiryDate,
            BigDecimal qtyPcs,
            BigDecimal costPerPcs,
            BigDecimal mrpPrice,
            Integer    pcsPerBox,
            Integer    pcsPerStrip,
            LocalDate  receivedDate,
            String     movementType,
            String     refType,
            Long       refMst,
            Long       refDtl,
            String     remarks
    ) {}

    /**
     * A withdrawal to be spread over lots.
     *
     * @param qty  in {@code unit}; converted to pieces against the product's
     *             current packing before allocation
     */
    record IssueCommand(
            Long       orgId,
            Long       branchId,
            Long       productId,
            BigDecimal qty,
            String     unit,
            String     movementType,
            String     refType,
            Long       refMst,
            Long       refDtl,
            LocalDate  movementDate,
            String     remarks
    ) {}
}
