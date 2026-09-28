package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrExpenseClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HrExpenseClaimRepository extends JpaRepository<HrExpenseClaim, Long> {

    Optional<HrExpenseClaim> findByPidExpenseClaimAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT c FROM HrExpenseClaim c
            WHERE c.pidBranch = :branchId
              AND c.status = 'Y'
              AND (:employeeId IS NULL OR c.pidEmployee = :employeeId)
              AND (:claimStatus = '' OR c.claimStatus = :claimStatus)
              AND (:from IS NULL OR c.claimDate >= :from)
              AND (:to   IS NULL OR c.claimDate <= :to)
            ORDER BY c.claimDate DESC, c.pidExpenseClaim DESC
           """)
    Page<HrExpenseClaim> search(@Param("branchId") Long branchId,
                                @Param("employeeId") Long employeeId,
                                @Param("claimStatus") String claimStatus,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to,
                                Pageable pageable);

    /** The approval queue. */
    List<HrExpenseClaim> findByPidBranchAndClaimStatusAndStatusOrderByClaimDate(
            Long pidBranch, String claimStatus, String status);

    long countByPidBranchAndClaimStatusAndStatus(Long pidBranch, String claimStatus, String status);

    List<HrExpenseClaim> findByPidEmployeeAndStatusOrderByClaimDateDesc(
            Long pidEmployee, String status);

    /**
     * What has already been claimed against one type on one date, across every
     * live claim but this one.
     *
     * <p>Read to enforce a daily cap, and only when a cap has been set. Excludes
     * rejected and cancelled claims: money that was refused is not money spent
     * against the day's allowance.
     */
    @Query("""
           SELECT COALESCE(SUM(l.claimedAmount), 0)
             FROM HrExpenseClaimLine l
            WHERE l.claim.pidEmployee = :employeeId
              AND l.pidExpenseType = :typeId
              AND l.expenseDate = :on
              AND l.status = 'Y'
              AND l.claim.status = 'Y'
              AND l.claim.claimStatus IN ('DRAFT','SUBMITTED','APPROVED','PAID')
              AND (:excludeClaimId IS NULL OR l.claim.pidExpenseClaim <> :excludeClaimId)
           """)
    BigDecimal claimedOnDay(@Param("employeeId") Long employeeId,
                            @Param("typeId") Long typeId,
                            @Param("on") LocalDate on,
                            @Param("excludeClaimId") Long excludeClaimId);

    /** Whether a type has ever been used, so retiring it can say so. */
    @Query("""
           SELECT COUNT(l) FROM HrExpenseClaimLine l
            WHERE l.pidExpenseType = :typeId AND l.status = 'Y'
           """)
    long countLinesOfType(@Param("typeId") Long typeId);
}
