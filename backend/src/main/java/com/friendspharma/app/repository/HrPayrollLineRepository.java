package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrPayrollLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrPayrollLineRepository extends JpaRepository<HrPayrollLine, Long> {

    List<HrPayrollLine> findByPayroll_PidPayrollOrderByEmpName(Long pidPayroll);

    Optional<HrPayrollLine> findByPayroll_PidPayrollAndPidEmployee(Long pidPayroll, Long pidEmployee);

    /**
     * The gross on somebody's most recent posted payslip.
     *
     * <p>Read by a gratuity policy valued on gross. A posted payslip is a figure
     * somebody signed; re-deriving the gross from the salary structure instead
     * would be a second implementation of the payroll arithmetic, and the first
     * time a structure changed the two would disagree with nobody able to say
     * which was right.
     *
     * <p>A list rather than an Optional so the caller takes the first without a
     * getSingleResult that throws when a month was run twice.
     */
    @Query("""
           SELECT l.grossSalary FROM HrPayrollLine l
            WHERE l.pidEmployee = :employeeId
              AND l.status = 'Y'
              AND l.payroll.payrollStatus IN ('POSTED','PAID')
            ORDER BY l.payroll.pidPeriod DESC, l.pidPayrollLine DESC
           """)
    List<java.math.BigDecimal> findLastPostedGross(@Param("employeeId") Long employeeId);

    /** One person's payslip history, newest first. */
    @Query("""
           SELECT l FROM HrPayrollLine l
            WHERE l.pidEmployee = :employeeId
              AND l.status = 'Y'
            ORDER BY l.payroll.pidPeriod DESC, l.pidPayrollLine DESC
           """)
    List<HrPayrollLine> findForEmployee(@Param("employeeId") Long employeeId);

    /**
     * Payslips on a run that still owe something.
     *
     * <p>What the salary payment screen lists, and what a bulk pay run walks.
     */
    @Query("""
           SELECT l FROM HrPayrollLine l
            WHERE l.payroll.pidPayroll = :payrollId
              AND l.status = 'Y'
              AND l.paidAmount < l.netSalary
            ORDER BY l.empName
           """)
    List<HrPayrollLine> findUnpaid(@Param("payrollId") Long payrollId);

    /** Salary expense by department for a run, for the register and for Accounts. */
    @Query("""
           SELECT l.pidDepartment, SUM(l.grossSalary), SUM(l.netSalary), COUNT(l)
             FROM HrPayrollLine l
            WHERE l.payroll.pidPayroll = :payrollId AND l.status = 'Y'
            GROUP BY l.pidDepartment
           """)
    List<Object[]> sumByDepartment(@Param("payrollId") Long payrollId);
}
