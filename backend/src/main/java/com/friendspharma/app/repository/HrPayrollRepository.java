package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrPayroll;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrPayrollRepository extends JpaRepository<HrPayroll, Long> {

    Optional<HrPayroll> findByPidPayrollAndPidBranch(Long id, Long pidBranch);

    /**
     * The live run for a period, if there is one.
     *
     * <p>Cancelled runs are excluded, matching {@code uq_hr_payroll_live_run}:
     * a month that was started and abandoned can be started again, and the
     * abandoned run stays as evidence that it happened.
     */
    @Query("""
           SELECT p FROM HrPayroll p
            WHERE p.pidBranch = :branchId
              AND p.pidPeriod = :periodId
              AND p.status = 'Y'
              AND p.payrollStatus <> 'CANCELLED'
           """)
    Optional<HrPayroll> findLiveRun(@Param("branchId") Long branchId,
                                    @Param("periodId") Long periodId);

    @Query("""
           SELECT p FROM HrPayroll p
            WHERE p.pidBranch = :branchId
              AND p.status = 'Y'
              AND (:payStatus = '' OR p.payrollStatus = :payStatus)
            ORDER BY p.pidPeriod DESC, p.pidPayroll DESC
           """)
    Page<HrPayroll> search(@Param("branchId") Long branchId,
                           @Param("payStatus") String payStatus,
                           Pageable pageable);

    List<HrPayroll> findByPidBranchAndStatusOrderByPidPayrollDesc(Long pidBranch, String status);

    long countByPidBranchAndPayrollStatusAndStatus(Long pidBranch, String payrollStatus, String status);
}
