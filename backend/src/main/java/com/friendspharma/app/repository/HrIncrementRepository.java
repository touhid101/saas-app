package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrIncrement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HrIncrementRepository extends JpaRepository<HrIncrement, Long> {

    Optional<HrIncrement> findByPidIncrementAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT i FROM HrIncrement i
            WHERE i.pidBranch = :branchId
              AND i.status = 'Y'
              AND (:employeeId IS NULL OR i.pidEmployee = :employeeId)
              AND (:incStatus = '' OR i.incrementStatus = :incStatus)
            ORDER BY i.effectiveDate DESC, i.pidIncrement DESC
           """)
    Page<HrIncrement> search(@Param("branchId") Long branchId,
                             @Param("employeeId") Long employeeId,
                             @Param("incStatus") String incStatus,
                             Pageable pageable);

    /** The approval queue. */
    List<HrIncrement> findByPidBranchAndIncrementStatusAndStatusOrderByEffectiveDate(
            Long pidBranch, String incrementStatus, String status);

    long countByPidBranchAndIncrementStatusAndStatus(
            Long pidBranch, String incrementStatus, String status);

    /**
     * One person's salary history, newest first.
     *
     * <p>{@code HR_EMPLOYEES.BASIC_SALARY} holds only the current figure; these
     * rows are the record of how it got there.
     */
    List<HrIncrement> findByPidEmployeeAndStatusOrderByEffectiveDateDesc(
            Long pidEmployee, String status);

    /** Approved but not yet applied, and now due — what the screen offers to apply. */
    @Query("""
           SELECT i FROM HrIncrement i
            WHERE i.pidBranch = :branchId
              AND i.status = 'Y'
              AND i.incrementStatus = 'APPROVED'
              AND i.effectiveDate <= :on
            ORDER BY i.effectiveDate
           """)
    List<HrIncrement> findDue(@Param("branchId") Long branchId, @Param("on") LocalDate on);

    /** Guards uq_hr_increment_live with a sentence rather than a constraint name. */
    @Query("""
           SELECT i FROM HrIncrement i
            WHERE i.pidEmployee = :employeeId
              AND i.effectiveDate = :on
              AND i.status = 'Y'
              AND i.incrementStatus IN ('DRAFT','SUBMITTED','APPROVED')
              AND (:excludeId IS NULL OR i.pidIncrement <> :excludeId)
           """)
    List<HrIncrement> findLiveFor(@Param("employeeId") Long employeeId,
                                  @Param("on") LocalDate on,
                                  @Param("excludeId") Long excludeId);
}
