package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrSalaryPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HrSalaryPaymentRepository extends JpaRepository<HrSalaryPayment, Long> {

    Optional<HrSalaryPayment> findByPidSalaryPaymentAndPidBranch(Long id, Long pidBranch);

    List<HrSalaryPayment> findByPidPayrollAndStatusOrderByPaymentDateDesc(Long pidPayroll, String status);

    List<HrSalaryPayment> findByPidPayrollLineAndStatus(Long pidPayrollLine, String status);

    @Query("""
           SELECT p FROM HrSalaryPayment p
            WHERE p.pidBranch = :branchId
              AND p.status = 'Y'
              AND p.paymentDate BETWEEN :from AND :to
            ORDER BY p.paymentDate DESC, p.pidSalaryPayment DESC
           """)
    List<HrSalaryPayment> findBranchRange(@Param("branchId") Long branchId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /**
     * What has actually been handed over against a run.
     *
     * <p>Summed from the payment rows rather than read from {@code TOTAL_PAID}
     * on the run, so cancelling a payment corrects the total by arithmetic
     * instead of by a second update that could be missed.
     */
    @Query("""
           SELECT COALESCE(SUM(p.amount), 0) FROM HrSalaryPayment p
            WHERE p.pidPayroll = :payrollId
              AND p.status = 'Y'
              AND p.paymentStatus = 'PAID'
           """)
    java.math.BigDecimal sumPaidFor(@Param("payrollId") Long payrollId);
}
