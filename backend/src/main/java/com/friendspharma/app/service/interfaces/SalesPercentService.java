package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.SalesPercentRequest;
import com.friendspharma.app.dto.response.SalesPercentResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * Sales% maintenance — the per-unit percentages that derive a product's sale
 * price from its MRP.
 *
 * <p>Distinct from {@link SalesDiscountService}, which is a per-line discount
 * rules engine. This is pricing: what a box, strip or piece of a product costs
 * a regular or special customer. Both mechanisms coexist.
 *
 * <p>Every method scopes itself to the caller's organisation, resolved from the
 * JWT inside the implementation — no method takes an orgId.
 */
public interface SalesPercentService {

    /** Paginated, filtered, sorted pricing grid. */
    PageResponse<SalesPercentResponse> search(SalesPercentFilter filter, Pageable pageable);

    /** One product's six percentages and the prices they produce. */
    SalesPercentResponse getByProductId(Long productId);

    /** Sets one product's percentages. */
    SalesPercentResponse update(Long productId, SalesPercentRequest request);

    /**
     * Applies one set of percentages across many products — how pricing is
     * actually rolled out, by manufacturer or category rather than one product
     * at a time. Targets are either an explicit id list or a scope.
     */
    SalesPercentResponse.BulkResult applyBulk(SalesPercentRequest.Bulk request);

    /**
     * How many products a scope would touch, without touching them, so the UI
     * can state the blast radius before the user commits.
     */
    long countInScope(SalesPercentRequest.Scope scope);

    /** Dropdowns for the filter bar: companies, categories, generics. */
    Map<String, List<?>> getFilterLookups();

    /**
     * Filters for the pricing grid.
     *
     * @param configured null = all, TRUE = has at least one percentage,
     *                   FALSE = nothing configured yet (the "needs pricing" list)
     */
    record SalesPercentFilter(
            String  search,
            Long    companyId,
            Long    categoryId,
            Long    genericId,
            String  status,
            Boolean configured,
            /**
             * Only rows whose price, after the discount, has fallen to or below
             * what the stock cost. The loss-making lines.
             *
             * <p>Cannot be a query predicate: the cost is a weighted average over
             * the batches on hand, not a column on the product. See
             * {@code SalesPercentServiceImpl.search}.
             */
            Boolean belowCost
    ) {}
}
