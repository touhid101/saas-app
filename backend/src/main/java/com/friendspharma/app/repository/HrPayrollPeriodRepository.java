package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrPayrollPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HrPayrollPeriodRepository extends JpaRepository<HrPayrollPeriod, Long> {

    Optional<HrPayrollPeriod> findByPidPeriodAndPidBranch(Long pidPeriod, Long pidBranch);

    Optional<HrPayrollPeriod> findByPidBranchAndPeriodYearAndPeriodMonthAndStatus(
            Long pidBranch, Integer year, Integer month, String status);

    List<HrPayrollPeriod> findByPidBranchAndStatusOrderByPeriodYearDescPeriodMonthDesc(
            Long pidBranch, String status);

    List<HrPayrollPeriod> findByPidBranchAndPeriodYearAndStatusOrderByPeriodMonth(
            Long pidBranch, Integer year, String status);

    /**
     * Periods covering a date at this branch.
     *
     * <p>Used to refuse a period that overlaps one already defined: two periods
     * containing the same day would let one day of attendance be paid twice.
     */
    List<HrPayrollPeriod> findByPidBranchAndStatusAndPeriodFromLessThanEqualAndPeriodToGreaterThanEqual(
            Long pidBranch, String status, LocalDate onOrBefore, LocalDate onOrAfter);
}
