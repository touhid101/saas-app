package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosProductInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosProductInfoRepository extends JpaRepository<PosProductInfo, Long>, JpaSpecificationExecutor<PosProductInfo> {
    /**
     * A branch's own products plus the shared catalogue.
     *
     * <p>A product carrying a branch belongs to that pharmacy alone. A product
     * carrying none is the shared catalogue, which is every row today because the
     * column was never populated — so this keeps the existing rows visible while
     * making anything a pharmacy creates private to it.
     */
    @Query("""
           SELECT p FROM PosProductInfo p
            WHERE p.pidOrg = :orgId
              AND (p.pidBranch IS NULL OR p.pidBranch = :branchId)
              AND p.status = :status
            ORDER BY p.productName
           """)
    List<PosProductInfo> findForBranch(@Param("orgId") Long orgId,
                                       @Param("branchId") Long branchId,
                                       @Param("status") String status);
    /**
     * The marketplace catalogue (docs/ecommerce Phase 3): live products of the
     * online pharmacies, matched on name, generic, company or code. A product
     * with no branch belongs to every branch of its organisation, as
     * {@link #findForBranch} reads it; the caller expands it per online branch.
     */
    @Query("""
           SELECT p FROM PosProductInfo p
             LEFT JOIN p.genericInfo g
             LEFT JOIN p.companyInfo c
             LEFT JOIN p.categoryInfo k
            WHERE p.status = 'Y'
              AND p.supersededBy IS NULL
              AND (p.pidBranch IN :branches OR (p.pidBranch IS NULL AND p.pidOrg IN :orgs))
              AND (:category = '' OR LOWER(k.categoryName) = LOWER(:category))
              AND (:q = ''
                   OR LOWER(p.productName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(g.genericName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.companyName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(p.productCode) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR p.barcode = :q)
            ORDER BY p.productName, p.pidProduct
           """)
    List<PosProductInfo> searchForMarketplace(@Param("branches") java.util.Collection<Long> branches,
                                              @Param("orgs") java.util.Collection<Long> orgs,
                                              @Param("q") String q,
                                              @Param("category") String category);

    /**
     * Every live row for the same medicine at the given branches — the price
     * comparison (docs/ecommerce Phase 4). Matched on the shared catalogue
     * entry where the pharmacies imported one, else on the product name, which
     * carries the strength in this catalogue ("Ace 500mg").
     */
    @Query("""
           SELECT p FROM PosProductInfo p
            WHERE p.status = 'Y'
              AND p.supersededBy IS NULL
              AND (p.pidBranch IN :branches OR (p.pidBranch IS NULL AND p.pidOrg IN :orgs))
              AND ((:globalId IS NOT NULL AND p.pidGlobalProduct = :globalId)
                   OR (:globalId IS NULL AND LOWER(TRIM(p.productName)) = :name))
           """)
    List<PosProductInfo> findSameMedicine(@Param("branches") java.util.Collection<Long> branches,
                                          @Param("orgs") java.util.Collection<Long> orgs,
                                          @Param("globalId") Long globalId,
                                          @Param("name") String lowerTrimmedName);

    Optional<PosProductInfo> findByBarcodeAndPidOrg(String barcode, Long pidOrg);
    /**
     * [V72] The bulk importer's match key — a List, not an Optional.
     *
     * <p>SKU stopped being unique when its index was dropped, so a derived
     * {@code Optional} finder would now throw
     * {@code IncorrectResultSizeDataAccessException} the first time an import
     * met a SKU that two products share. That is a failure of the whole import
     * run, triggered by data the system now considers valid.
     *
     * <p>Ordered so the caller takes the same row every time rather than
     * whatever the planner returns first — an importer that updates a different
     * product on each run would be worse than one that fails.
     */
    @Query("SELECT p FROM PosProductInfo p " +
           "WHERE p.pidOrg = :pidOrg AND p.sku = :sku AND p.supersededBy IS NULL " +
           "ORDER BY p.pidProduct")
    List<PosProductInfo> findAllBySkuAndPidOrg(@Param("sku") String sku,
                                               @Param("pidOrg") Long pidOrg);
    Optional<PosProductInfo> findByBarcodeAndStatus(String barcode, String status);
    Optional<PosProductInfo> findBySkuAndStatus(String sku, String status);
    List<PosProductInfo> findByPidOrgAndStatusOrderByProductName(Long pidOrg, String status);
    List<PosProductInfo> findByPidCategoryAndStatusOrderByProductName(Long pidCategory, String status);

    @Query("SELECT p FROM PosProductInfo p WHERE p.pidOrg = :orgId AND p.status = 'Y' " +
           "AND (LOWER(p.productName) LIKE LOWER(CONCAT('%',:q,'%')) OR p.barcode LIKE CONCAT('%',:q,'%'))")
    List<PosProductInfo> searchByNameOrBarcode(@Param("orgId") Long orgId, @Param("q") String q);

    /**
     * The branch's own products, searched.
     *
     * <p>The org-wide version above is what line entry used to call, so typing a
     * name offered products belonging to other branches of the same organisation
     * while clearing the box offered only this branch's — two different answers
     * from one picker. A product from another pharmacy cannot be purchased or
     * requisitioned here, so offering it is offering a line that will fail.
     *
     * <p>{@code pidBranch IS NULL} is kept because a product with no branch is
     * shared across the organisation by design, and {@code findForBranch} has
     * always treated it that way.
     *
     * <p>A blank term matches everything, so one query serves both browsing the
     * list and searching it — there is no second definition to drift.
     */
    @Query("""
           SELECT p FROM PosProductInfo p
            WHERE p.pidOrg = :orgId
              AND (p.pidBranch IS NULL OR p.pidBranch = :branchId)
              AND (:activeOnly = FALSE OR p.status = 'Y')
              /* [V66] A superseded version is history, not a product. Without
                 this the line-entry picker offered every retired copy beside
                 the live one — indistinguishable in the list, and buyable. */
              AND p.supersededBy IS NULL
              AND (:companyId IS NULL OR p.pidCompany = :companyId)
              AND (:q = ''
                   OR LOWER(p.productName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(p.productCode) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR p.barcode LIKE CONCAT('%', :q, '%')
                   OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY p.productName
           """)
    List<PosProductInfo> searchForBranch(@Param("orgId") Long orgId,
                                         @Param("branchId") Long branchId,
                                         @Param("q") String q,
                                         @Param("activeOnly") boolean activeOnly,
                                         @Param("companyId") Long companyId);

    /**
     * The manufacturers this branch actually stocks, with how many products each.
     *
     * <p>Derived from the branch's own products rather than read from the company
     * master, so every name offered has something behind it. A list of every
     * company in the organisation would include makers this pharmacy has never
     * carried, and picking one would add nothing.
     */
    @Query("""
           SELECT c.pidCompany, c.companyName, COUNT(p)
             FROM PosProductInfo p
             JOIN PosCompanyInfo c ON c.pidCompany = p.pidCompany
            WHERE p.pidOrg = :orgId
              AND (p.pidBranch IS NULL OR p.pidBranch = :branchId)
              AND (:activeOnly = FALSE OR p.status = 'Y')
              AND p.supersededBy IS NULL
            GROUP BY c.pidCompany, c.companyName
            ORDER BY c.companyName
           """)
    List<Object[]> companiesForBranch(@Param("orgId") Long orgId,
                                      @Param("branchId") Long branchId,
                                      @Param("activeOnly") boolean activeOnly);

    @Query("SELECT p FROM PosProductInfo p WHERE p.pidOrg = :orgId AND p.status = 'Y' " +
           "AND p.stockQty <= p.alertQty")
    List<PosProductInfo> findLowStockProducts(@Param("orgId") Long orgId);

    @Query("SELECT p FROM PosProductInfo p WHERE p.pidOrg = :orgId AND p.status = 'Y' " +
           "AND (p.stockQty IS NULL OR p.stockQty <= 0)")
    List<PosProductInfo> findOutOfStockProducts(@Param("orgId") Long orgId);

    /* ── Product Master (V4) ─────────────────────────────────────────────────
       Uniqueness probes. Each takes an excludeId so the same query serves both
       create (excludeId = -1) and update (excludeId = the row being edited),
       which removes the "product conflicts with itself" class of bug.
       Comparisons are UPPER()-based to match the case-insensitive unique
       indexes UX_PRODUCT_SKU_ORG / UX_PRODUCT_BARCODE_ORG.                    */

    /* [V72] There is deliberately no SKU probe here. A SKU is not unique in this
       system: it is a free-text shelf or supplier reference, several products
       may legitimately carry the same one, and the unique index that used to
       enforce it was dropped in V72. Barcode is unchanged and still unique. */

    @Query("SELECT COUNT(p) > 0 FROM PosProductInfo p " +
           "WHERE p.pidOrg = :orgId AND UPPER(p.barcode) = UPPER(:barcode) AND p.pidProduct <> :excludeId " +
           "AND p.supersededBy IS NULL")
    boolean existsByBarcodeIgnoreCase(@Param("orgId") Long orgId,
                                      @Param("barcode") String barcode,
                                      @Param("excludeId") Long excludeId);

    /**
     * Business-key duplicate probe: the same medicine name from the same
     * manufacturer with the same generic is almost certainly a double entry.
     * Product name alone is deliberately NOT unique — the same medicine is
     * legitimately sold by several companies.
     */
    @Query("SELECT COUNT(p) > 0 FROM PosProductInfo p " +
           "WHERE p.pidOrg = :orgId AND UPPER(TRIM(p.productName)) = UPPER(TRIM(:name)) " +
           "AND p.pidCompany = :companyId AND p.pidGeneric = :genericId AND p.pidProduct <> :excludeId " +
           "AND p.supersededBy IS NULL")
    boolean existsByBusinessKey(@Param("orgId") Long orgId,
                                @Param("name") String name,
                                @Param("companyId") Long companyId,
                                @Param("genericId") Long genericId,
                                @Param("excludeId") Long excludeId);

    /**
     * The same guard for a product with no generic — a general item.
     *
     * <p>A separate query rather than a null-tolerant one: {@code = :genericId}
     * with a null parameter is never true in SQL, so the query above would
     * report "no duplicate" for every general product and let the same soap be
     * created any number of times. IS NULL has to be written out.
     */
    @Query("SELECT COUNT(p) > 0 FROM PosProductInfo p " +
           "WHERE p.pidOrg = :orgId AND UPPER(TRIM(p.productName)) = UPPER(TRIM(:name)) " +
           "AND p.pidCompany = :companyId AND p.pidGeneric IS NULL AND p.pidProduct <> :excludeId " +
           "AND p.supersededBy IS NULL")
    boolean existsByBusinessKeyNoGeneric(@Param("orgId") Long orgId,
                                         @Param("name") String name,
                                         @Param("companyId") Long companyId,
                                         @Param("excludeId") Long excludeId);

    Optional<PosProductInfo> findByProductCode(String productCode);

    @Query("SELECT p FROM PosProductInfo p WHERE p.pidProduct = :id AND p.pidOrg = :orgId")
    Optional<PosProductInfo> findByIdAndOrg(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * [V66] Every version of one product, newest first.
     *
     * <p>By root id rather than by walking SUPERSEDED_BY, so it answers the same
     * list whichever version the caller is holding — and in one query rather
     * than one per step of the chain.
     */
    @Query("SELECT p FROM PosProductInfo p "
         + "WHERE COALESCE(p.pidRootProduct, p.pidProduct) = :rootId "
         + "ORDER BY p.versionNo DESC")
    List<PosProductInfo> findVersionChain(@Param("rootId") Long rootId);

    /**
     * How many of this branch's products one manufacturer supplies.
     *
     * <p>For the purchase screen, which states the number the moment a supplier
     * is chosen — "Beximco Pharmaceuticals · 30 products" — so a buyer knows
     * whether the list they are about to search is the one they expect.
     */
    @Query("SELECT COUNT(p) FROM PosProductInfo p "
         + "WHERE p.pidOrg = :orgId "
         + "AND (p.pidBranch IS NULL OR p.pidBranch = :branchId) "
         + "AND p.pidCompany = :companyId "
         + "AND p.supersededBy IS NULL")
    long countForBranchByCompany(@Param("orgId") Long orgId,
                                 @Param("branchId") Long branchId,
                                 @Param("companyId") Long companyId);

    /**
     * Is this product name already on this branch's list?
     *
     * <p>Trimmed and case-folded on both sides, so " napa 500mg " and
     * "Napa 500mg" are the same name — which is what a person means by "the
     * same name", and what the previous rule (name + company + generic) missed
     * whenever the second entry named a different manufacturer.
     *
     * <p><b>Per branch, not per organisation.</b> A pharmacy cannot hold the
     * same medicine twice; two pharmacies in one group each stocking "Ace 500mg"
     * is not a duplicate, it is two shops. Org-wide uniqueness would refuse the
     * second shop's own copy — there are five such pairs in this database today.
     *
     * <p><b>The shared list is its own list.</b> A product carrying no branch is
     * the organisation-wide catalogue that predates branch scoping, and 25 of
     * this database's branch products carry the same name as one of those older
     * shared rows. Comparing a branch row against them would have made all 25
     * uneditable the day this rule shipped — a new rule reaching backwards to
     * condemn existing data. So branch rows are compared with that branch's
     * rows, shared rows with shared rows, and neither with the other. Within
     * each list there is not one duplicate today, so the rule binds from here on
     * without stranding anything.
     *
     * <p>Superseded versions are ignored: an edit writes a new version carrying
     * the same name, and the retired row it replaced must not block it.
     */
    @Query("SELECT COUNT(p) > 0 FROM PosProductInfo p "
         + "WHERE p.pidOrg = :orgId "
         + "AND ((:branchId IS NULL AND p.pidBranch IS NULL) OR p.pidBranch = :branchId) "
         + "AND UPPER(TRIM(p.productName)) = UPPER(TRIM(:name)) "
         + "AND p.pidProduct <> :excludeId "
         + "AND p.supersededBy IS NULL")
    boolean existsByProductNameInBranch(@Param("orgId") Long orgId,
                                        @Param("branchId") Long branchId,
                                        @Param("name") String name,
                                        @Param("excludeId") Long excludeId);
}
