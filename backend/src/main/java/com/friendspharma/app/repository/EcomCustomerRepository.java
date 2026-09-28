package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCustomer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EcomCustomerRepository extends JpaRepository<EcomCustomer, Long> {

    Optional<EcomCustomer> findByMobileNo(String mobileNo);

    boolean existsByMobileNo(String mobileNo);

    @Query("SELECT COUNT(c) > 0 FROM EcomCustomer c WHERE LOWER(c.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT COUNT(c) > 0 FROM EcomCustomer c WHERE LOWER(c.email) = LOWER(:email) AND c.pidEcomCustomer <> :id")
    boolean existsByEmailIgnoreCaseAndIdNot(@Param("email") String email, @Param("id") Long id);

    /**
     * The console list. Every filter is applied in the query, not to the page,
     * so a search finds matches beyond page one (the Sales Activity rule).
     *
     * <p>An empty search is "" and never null. A null parameter inside
     * CONCAT('%', :q, '%') reaches PostgreSQL with no type, which resolves the
     * concatenation to bytea and then fails on lower(bytea) — so the screen
     * broke on the one thing it does most, opening with no search at all.
     */
    @Query("""
           SELECT c FROM EcomCustomer c
            WHERE (:status IS NULL OR c.approvalStatus = :status)
              AND (:type   IS NULL OR c.customerType   = :type)
              AND (:q = ''
                   OR LOWER(c.fullName)  LIKE LOWER(CONCAT('%', :q, '%'))
                   OR c.mobileNo         LIKE CONCAT('%', :q, '%')
                   OR LOWER(c.email)     LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.businessName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR c.customerCode     LIKE CONCAT('%', :q, '%'))
            ORDER BY CASE c.approvalStatus WHEN 'PENDING' THEN 0 ELSE 1 END, c.insertedDate DESC
           """)
    Page<EcomCustomer> search(@Param("status") String status,
                              @Param("type") String type,
                              @Param("q") String q,
                              Pageable pageable);

    @Query("SELECT c.approvalStatus, COUNT(c) FROM EcomCustomer c GROUP BY c.approvalStatus")
    List<Object[]> countByApprovalStatus();
}
