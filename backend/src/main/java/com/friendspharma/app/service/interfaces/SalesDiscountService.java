package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.request.SalesDiscountRequest;
import com.friendspharma.app.dto.response.SalesDiscountResponse;

import java.math.BigDecimal;
import java.util.List;

public interface SalesDiscountService {

    /** Create a new discount rule */
    SalesDiscountResponse.SalesDiscountDto create(SalesDiscountRequest.CreateSalesDiscountRequest req);

    /** Update an existing discount rule's percentage / active flag */
    SalesDiscountResponse.SalesDiscountDto update(Long pidDisc,
                                                   SalesDiscountRequest.UpdateSalesDiscountRequest req);

    /** Delete (hard-delete) a discount rule */
    void delete(Long pidDisc);

    /** List all active discount rules for an organisation */
    List<SalesDiscountResponse.SalesDiscountDto> listByOrg(Long orgId);

    /** List all rules (incl. inactive) for an org — admin view */
    List<SalesDiscountResponse.SalesDiscountDto> listAllByOrg(Long orgId);

    /** List rules filtered by company */
    List<SalesDiscountResponse.SalesDiscountDto> listByCompany(Long orgId, Long companyId);

    /** List rules filtered by product */
    List<SalesDiscountResponse.SalesDiscountDto> listByProduct(Long orgId, Long productId);

    /**
     * Resolve the effective discount percentage for a product + tier.
     * Returns ZERO if no rule is found.
     */
    BigDecimal resolveEffectiveDiscount(Long orgId, Long productId, Long companyId, String tier);

    /** Full effective-discount DTO (includes appliedAt level) */
    SalesDiscountResponse.EffectiveDiscountDto getEffectiveDiscount(
            SalesDiscountRequest.EffectiveDiscountQuery query);
}
