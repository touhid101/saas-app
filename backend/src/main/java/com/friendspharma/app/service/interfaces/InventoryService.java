package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.response.StockResponses;
import com.friendspharma.app.dto.response.StockResponses.*;
import com.friendspharma.app.entity.PosCart;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Inventory reporting over branch-wise batch stock.
 *
 * <p>Reads only. Every balance shown here comes from {@code POS_STOCK_BATCH},
 * which only {@link StockService} may write.
 *
 * <p><b>Branch is a parameter on every method and is never optional.</b> The
 * previous version accepted a {@code branchId} and ignored it, so every screen
 * reported every branch's stock as if it were yours. Callers resolve the branch
 * from the session; the controller is where that happens.
 */
public interface InventoryService {

    /**
     * The filters every inventory report accepts.
     *
     * <p>One record rather than four parameters repeated across seven methods —
     * adding the next filter then means one field, not seven signatures that can
     * drift apart.
     *
     * <p>{@code companyId}, {@code search} and {@code categoryId} are product
     * attributes; {@code batchNo} belongs to the stock itself. They are resolved
     * separately for that reason: the first three narrow which products are in
     * scope, the last narrows which lots of them.
     *
     * @param search    matches product name or SKU, and batch number where the
     *                  report has batches — one box for "find me that thing"
     * @param companyId manufacturer
     * @param batchNo   exact or partial batch reference
     */
    record StockFilter(
            String  search,
            Long    companyId,
            String  batchNo,
            Long    categoryId,
            Long    productId,
            /**
             * Drop products holding nothing.
             *
             * <p>For pickers that only offer what can actually be sold. The
             * ledger itself defaults to showing everything, because a report
             * that hides the zeroes cannot tell you what has run out.
             */
            boolean inStockOnly
    ) {
        public static StockFilter none() {
            return new StockFilter(null, null, null, null, null, false);
        }

        /** True when nothing here narrows the product set. */
        public boolean noProductNarrowing() {
            return blank(search) && companyId == null && categoryId == null && productId == null;
        }

        public boolean hasBatch() { return !blank(batchNo); }

        private static boolean blank(String s) { return s == null || s.isBlank(); }
    }

    /** Headline counters for one branch, in a single round trip. */
    StockDashboard getDashboard(Long branchId);

    /** Per-product stock rollup — the stock ledger screen. */
    PageResponse<ProductStockRow> getStockLedger(Long branchId, StockFilter filter, Pageable pageable);

    /** Lot-level stock. The batch is the row, as it is everywhere in this module. */
    PageResponse<BatchStockRow> getBatchStock(Long branchId, StockFilter filter,
                                              boolean includeEmpty, Pageable pageable);

    /**
     * Lots at or past {@code days} from expiry, soonest first.
     * Already-expired lots are included: they are the more urgent case.
     */
    PageResponse<BatchStockRow> getExpiringBatches(Long branchId, StockFilter filter,
                                                   int days, Pageable pageable);

    /** Lots with stock that have not sold in {@code idleDays}, most value first. */
    List<StockResponses.SlowMovingRow> getSlowMoving(Long branchId, StockFilter filter, int idleDays);

    /** Products whose sellable stock has fallen to or below their alert level. */
    List<ProductStockRow> getLowStockAlerts(Long branchId, StockFilter filter);

    /** Products with no sellable stock — expired-only counts as none. */
    List<ProductStockRow> getOutOfStockAlerts(Long branchId, StockFilter filter);

    /** Movement history: what entered and left, and why. */
    PageResponse<MovementRow> getMovementLedger(Long branchId, StockFilter filter,
                                                LocalDate fromDate, LocalDate toDate, Pageable pageable);

    /**
     * Product movement over a period — opening, every document type that moved
     * it, and closing.
     *
     * <p>Covers PU, PR, SL and SR together, which is what makes it the basis for
     * daily, monthly and actual-profit reporting: {@code salesCostValue} is the
     * COGS half of profit, taken from the cost of the batches actually
     * consumed rather than from an average.
     */
    MovementSummary getMovementSummary(Long branchId, StockFilter filter,
                                       LocalDate fromDate, LocalDate toDate);

    /**
     * Realised profit per product for a period.
     *
     * <p>Revenue is what the invoices actually charged; cost is what the
     * specific batches consumed actually cost, paired line by line through the
     * movement log. Not an average — an average product cost gives a
     * plausible-looking margin that is wrong for every individual sale.
     */
    ProfitSummary getProfit(Long branchId, StockFilter filter, LocalDate fromDate, LocalDate toDate);

    /** Companies and categories that actually hold stock in this branch, for the filter bar. */
    Map<String, List<?>> getFilterLookups(Long branchId);

    /**
     * What a sale of {@code qty} {@code unit} would consume, batch by batch,
     * without consuming it.
     */
    StockAllocation previewAllocation(Long branchId, Long productId, BigDecimal qty, String unit);

    /**
     * Lots whose stored balance disagrees with their own movement history.
     *
     * <p>Expected to be empty. Anything here means a balance was changed outside
     * {@link StockService}, and the movement log is the side to trust.
     */
    List<ReconciliationRow> reconcile();

    /** Creates a sales transaction from a POS cart. */
    Long createTransactionFromCart(PosCart cart, String paymentType, String remarks, Long userId);
}
