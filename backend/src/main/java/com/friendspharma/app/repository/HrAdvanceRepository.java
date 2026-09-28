package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrAdvance;
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
public interface HrAdvanceRepository extends JpaRepository<HrAdvance, Long> {

    Optional<HrAdvance> findByPidAdvanceAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT a FROM HrAdvance a
            WHERE a.pidBranch = :branchId
              AND a.status = 'Y'
              AND (:employeeId IS NULL OR a.pidEmployee = :employeeId)
              AND (:advStatus = '' OR a.advanceStatus = :advStatus)
            ORDER BY a.requestDate DESC, a.pidAdvance DESC
           """)
    Page<HrAdvance> search(@Param("branchId") Long branchId,
                           @Param("employeeId") Long employeeId,
                           @Param("advStatus") String advStatus,
                           Pageable pageable);

    /**
     * Advances payroll should be recovering, for the whole branch.
     *
     * <p>Read once per run rather than once per payslip. RECOVER_FROM is
     * honoured here rather than in the calculator so an advance agreed to start
     * coming back in November does not appear in the October run at all.
     */
    @Query("""
           SELECT a FROM HrAdvance a
            WHERE a.pidBranch = :branchId
              AND a.status = 'Y'
              AND a.advanceStatus IN ('PAID','RECOVERING')
              AND (a.recoverFrom IS NULL OR a.recoverFrom <= :upto)
            ORDER BY a.pidEmployee, a.requestDate
           """)
    List<HrAdvance> findRecoverable(@Param("branchId") Long branchId,
                                    @Param("upto") LocalDate upto);

    List<HrAdvance> findByPidEmployeeAndStatusOrderByRequestDateDesc(Long pidEmployee, String status);

    long countByPidBranchAndAdvanceStatusAndStatus(Long pidBranch, String advanceStatus, String status);

    /** The approval queue, read by the unified inbox. */
    List<HrAdvance> findByPidBranchAndAdvanceStatusAndStatusOrderByRequestDate(
            Long pidBranch, String advanceStatus, String status);

}
