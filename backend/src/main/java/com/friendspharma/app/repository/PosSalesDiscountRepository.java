package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosSalesDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosSalesDiscountRepository extends JpaRepository<PosSalesDiscount, Long> {

    List<PosSalesDiscount> findByPidOrgAndIsActive(Long orgId, Integer isActive);

    List<PosSalesDiscount> findByPidOrgAndPidCompanyAndIsActive(Long orgId, Long companyId, Integer isActive);

    List<PosSalesDiscount> findByPidOrgAndPidProductAndIsActive(Long orgId, Long productId, Integer isActive);

    Optional<PosSalesDiscount> findByPidOrgAndPidCompanyAndPidProductAndDiscountTier(
            Long orgId, Long companyId, Long productId, String tier);

    /**
     * Resolve effective discount for a product + tier.
     * Priority order: product-specific → company-specific → org-wide.
     */
    @Query("SELECT d FROM PosSalesDiscount d " +
           "WHERE d.pidOrg = :orgId AND d.discountTier = :tier AND d.isActive = 1 " +
           "AND (d.pidProduct = :productId OR " +
           "     (d.pidProduct IS NULL AND d.pidCompany = :companyId) OR " +
           "     (d.pidProduct IS NULL AND d.pidCompany IS NULL)) " +
           "ORDER BY CASE WHEN d.pidProduct IS NOT NULL THEN 0 " +
           "              WHEN d.pidCompany IS NOT NULL THEN 1 ELSE 2 END ASC")
    List<PosSalesDiscount> findEffectiveDiscount(@Param("orgId") Long orgId,
                                                  @Param("productId") Long productId,
                                                  @Param("companyId") Long companyId,
                                                  @Param("tier") String tier);
}
