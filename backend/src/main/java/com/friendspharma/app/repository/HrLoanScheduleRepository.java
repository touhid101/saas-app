package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrLoanSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface HrLoanScheduleRepository extends JpaRepository<HrLoanSchedule, Long> {

    List<HrLoanSchedule> findByLoan_PidLoanOrderByInstallmentNo(Long pidLoan);

    /**
     * Installments a payroll run should take.
     *
     * <p>Due on or before the end of the period, still open, and not already
     * marked against a run. That last condition is what makes recalculating a
     * payroll safe: an installment the previous calculation claimed is skipped
     * rather than taken twice.
     */
    @Query("""
           SELECT s FROM HrLoanSchedule s
            WHERE s.loan.pidLoan IN :loanIds
              AND s.status = 'Y'
              AND s.scheduleStatus IN ('PENDING','PARTIAL')
              AND s.dueDate <= :upto
              AND (s.pidPayroll IS NULL OR s.pidPayroll = :payrollId)
            ORDER BY s.loan.pidLoan, s.installmentNo
           """)
    List<HrLoanSchedule> findDueFor(@Param("loanIds") List<Long> loanIds,
                                    @Param("upto") LocalDate upto,
                                    @Param("payrollId") Long payrollId);

    /**
     * Everything a payroll run claimed, so recalculating can release it first.
     *
     * <p>Without this, a second calculation of the same run would find the
     * installments already taken and skip them, and the recalculated payslip
     * would be short by exactly the recovery the first attempt made.
     */
    List<HrLoanSchedule> findByPidPayroll(Long pidPayroll);
}
