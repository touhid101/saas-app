package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrPfLedger;
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

/** Movements in the provident fund. */
@Repository
public interface HrPfLedgerRepository extends JpaRepository<HrPfLedger, Long> {

    Optional<HrPfLedger> findByPidPfLedgerAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT l FROM HrPfLedger l
            WHERE l.pidBranch = :branchId
              AND l.status = 'Y'
              AND (:employeeId IS NULL OR l.pidEmployee = :employeeId)
              AND (:entryType = '' OR l.entryType = :entryType)
              AND (:from IS NULL OR l.entryDate >= :from)
              AND (:to   IS NULL OR l.entryDate <= :to)
            ORDER BY l.entryDate DESC, l.pidPfLedger DESC
           """)
    Page<HrPfLedger> search(@Param("branchId") Long branchId,
                            @Param("employeeId") Long employeeId,
                            @Param("entryType") String entryType,
                            @Param("from") LocalDate from,
                            @Param("to") LocalDate to,
                            Pageable pageable);

    /** One person's statement, oldest first, which is the order a balance builds in. */
    List<HrPfLedger> findByPidEmployeeAndStatusOrderByEntryDateAscPidPfLedgerAsc(
            Long pidEmployee, String status);

    /**
     * One person's balance.
     *
     * <p>Summed in the database rather than in Java because a fund that has run
     * for years is more rows than a screen should pull back to add up. The CASE
     * is what turns the magnitudes into a signed total — see {@code HrPfLedger}.
     */
    @Query("""
           SELECT COALESCE(SUM(CASE WHEN l.entryType IN ('WITHDRAWAL','SETTLEMENT')
                                    THEN -(l.employeeAmount + l.employerAmount)
                                    ELSE   (l.employeeAmount + l.employerAmount) END), 0)
             FROM HrPfLedger l
            WHERE l.pidEmployee = :employeeId AND l.status = 'Y'
           """)
    BigDecimal balanceOf(@Param("employeeId") Long employeeId);

    /** What a payroll run already contributed, so posting twice cannot double it. */
    List<HrPfLedger> findByPidPayrollAndEntryTypeAndStatus(
            Long pidPayroll, String entryType, String status);

    long countByPidPayrollAndEntryTypeAndStatus(Long pidPayroll, String entryType, String status);

    /** Everybody's balance at a branch, for the fund summary. */
    @Query("""
           SELECT l.pidEmployee,
                  COALESCE(SUM(CASE WHEN l.entryType IN ('WITHDRAWAL','SETTLEMENT')
                                    THEN -l.employeeAmount ELSE l.employeeAmount END), 0),
                  COALESCE(SUM(CASE WHEN l.entryType IN ('WITHDRAWAL','SETTLEMENT')
                                    THEN -l.employerAmount ELSE l.employerAmount END), 0)
             FROM HrPfLedger l
            WHERE l.pidBranch = :branchId AND l.status = 'Y'
            GROUP BY l.pidEmployee
           """)
    List<Object[]> balancesByEmployee(@Param("branchId") Long branchId);
}
