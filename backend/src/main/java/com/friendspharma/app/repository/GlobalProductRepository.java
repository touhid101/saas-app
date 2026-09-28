package com.friendspharma.app.repository;

import org.springframework.data.jpa.repository.Modifying;
import com.friendspharma.app.entity.GlobalProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** The Super Admin's medicine catalogue. */
@Repository
public interface GlobalProductRepository extends JpaRepository<GlobalProduct, Long> {

    /**
     * The catalogue, searchable by name, generic, company or code.
     *
     * <p>Blank search matches everything rather than nothing: an empty filter box
     * means "show me the catalogue", not "show me the products with no name".
     */
    @Query("""
           SELECT g FROM GlobalProduct g
            WHERE g.isActive = 1
              AND (:q = ''
                   OR LOWER(g.productName)  LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(g.genericName)  LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(g.companyName)  LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(g.productCode)  LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:company = '' OR g.companyName = :company)
            ORDER BY g.productName
           """)
    Page<GlobalProduct> search(@Param("q") String q,
                               @Param("company") String company,
                               Pageable pageable);

    /** The manufacturers represented in the catalogue, for the filter. */
    @Query("""
           SELECT DISTINCT g.companyName FROM GlobalProduct g
            WHERE g.isActive = 1 AND g.companyName IS NOT NULL
            ORDER BY g.companyName
           """)
    List<String> companies();

    /**
     * Which of these a branch already holds.
     *
     * <p>Asked once before an import rather than once per product: importing two
     * thousand medicines should be one question to the database, not two thousand.
     */
    @Query(value = """
           SELECT p.PID_GLOBAL_PRODUCT
             FROM PHARMA.POS_PRODUCT_INFO p
            WHERE p.PID_BRANCH = :branchId
              AND p.PID_GLOBAL_PRODUCT IS NOT NULL
           """, nativeQuery = true)
    List<Long> alreadyHeldBy(@Param("branchId") Long branchId);

    /**
     * The names and codes a branch already stocks, lower-cased.
     *
     * <p>A branch that typed "Napa 500mg" in by hand has no link to the catalogue
     * row of the same name, so matching on the link alone let the import try to
     * create a second one — and the unique index on (branch, product code)
     * rejected the whole import with a constraint error nobody could act on.
     */
    @Query(value = """
           SELECT LOWER(p.PRODUCT_NAME) FROM PHARMA.POS_PRODUCT_INFO p
            WHERE p.PID_BRANCH = :branchId AND p.PRODUCT_NAME IS NOT NULL
            UNION
           SELECT LOWER(p.PRODUCT_CODE) FROM PHARMA.POS_PRODUCT_INFO p
            WHERE p.PID_BRANCH = :branchId AND p.PRODUCT_CODE IS NOT NULL
           """, nativeQuery = true)
    List<String> namesAndCodesHeldBy(@Param("branchId") Long branchId);

    /* Used before retiring a master list entry: a catalogue row must never be
       left naming something that no longer exists. */
    long countByPidGlobalCompany(Long id);
    long countByPidGlobalGeneric(Long id);
    long countByPidGlobalCategory(Long id);
    long countByPidGlobalSubCategory(Long id);
    long countByPidGlobalUnit(Long id);
    long countByPidGlobalLeaf(Long id);
    long countByPidGlobalBox(Long id);

    /* ── keeping the denormalised names in step with the lists ──
       GLOBAL_PRODUCT stores both the id and the name. The name is what the import
       reads, so renaming a list entry has to rewrite it on every catalogue row
       pointing at that entry — otherwise the next pharmacy to import inherits the
       old spelling, which is the exact problem the lists were introduced to end. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE GlobalProduct g SET g.companyName = :name WHERE g.pidGlobalCompany = :id")
    int renameCompany(@Param("id") Long id, @Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE GlobalProduct g SET g.genericName = :name WHERE g.pidGlobalGeneric = :id")
    int renameGeneric(@Param("id") Long id, @Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE GlobalProduct g SET g.categoryName = :name WHERE g.pidGlobalCategory = :id")
    int renameCategory(@Param("id") Long id, @Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE GlobalProduct g SET g.subCategoryName = :name WHERE g.pidGlobalSubCategory = :id")
    int renameSubCategory(@Param("id") Long id, @Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE GlobalProduct g SET g.unitName = :name WHERE g.pidGlobalUnit = :id")
    int renameUnit(@Param("id") Long id, @Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
 @Query("UPDATE GlobalProduct g SET g.pcsPerLeaf = :pcs WHERE g.pidGlobalLeaf = :id")
       int resizeLeaf(@Param("id") Long id, @Param("pcs") Integer pcs);
   
       @Modifying(clearAutomatically = true, flushAutomatically = true)
       @Query("UPDATE GlobalProduct g SET g.leafPerBox = :leaves WHERE g.pidGlobalBox = :id")
       int resizeBox(@Param("id") Long id, @Param("leaves") Integer leaves);
}
