package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.response.StockResponses.StockAllocation;

import java.util.List;

/**
 * Posts approved transactions to stock.
 *
 * <p>One entry point for all four document types, so the rule "which way does
 * stock move" lives in exactly one place instead of being restated — and
 * eventually contradicted — in the purchase, sales and return services.
 *
 * <pre>
 *   PU  purchase          IN   into the batch named on the line
 *   PR  purchase return   OUT  from the batch named on the line
 *   SL  sale              OUT  batch-wise FEFO across the branch's lots
 *   SR  sales return      IN   back into the batch the sale consumed
 * </pre>
 *
 * <p>Purchases and their returns name their own batch: the goods are physically
 * identified by the delivery they arrived in. Sales do not — the customer asks
 * for twenty tablets, not for a lot number — so the batch is chosen by FEFO and
 * then <em>recorded on the line</em>, which is what lets the eventual sales
 * return go back to the right lot at the right cost.
 *
 * <p>Every posting is idempotent per detail line. Approving a document twice
 * moves stock once.
 */
public interface StockPostingService {

    /**
     * Posts every line of an approved transaction.
     *
     * <p>Call this at the moment a document becomes stock-affecting — approval,
     * not creation. A draft has not moved anything.
     *
     * @return one result per line, in line order
     */
    List<PostingResult> post(Long tranMstId);

    /**
     * Reverses everything a transaction posted, by writing opposing movements.
     *
     * <p>For cancelling an approved document. Refuses when the stock has since
     * been sold on, because at that point there is nothing left to give back and
     * the honest correction is a return document, not a cancellation.
     */
    void reverse(Long tranMstId, String remarks);

    /**
     * Outcome for one line.
     *
     * @param allocation  present for outbound lines, showing which lots were
     *                    consumed; {@code null} for inbound ones, which have
     *                    only the single batch they arrived in
     */
    record PostingResult(
            Long            tranDtlId,
            Long            productId,
            String          batchNo,
            String          movementType,
            String          direction,
            java.math.BigDecimal qtyPcs,
            StockAllocation allocation
    ) {}
}
