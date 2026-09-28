package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Holidays — the organisation's and each branch's own.
 *
 * <p>Every query includes the organisation-wide rows through the null branch,
 * which is the whole reason {@code PID_BRANCH} is nullable.
 */
@Repository
public interface HrHolidayRepository extends JpaRepository<HrHoliday, Long> {

    /**
     * Holidays in force for a branch over a range.
     *
     * <p>Read once for a window rather than per date: payroll asks about
     * thirty-one of them and a leave request about however many it spans.
     */
    @Query("""
           SELECT h FROM HrHoliday h
            WHERE h.pidOrg = :orgId
              AND h.status = 'Y'
              AND h.holidayDate BETWEEN :from AND :to
              AND (h.pidBranch IS NULL OR h.pidBranch = :branchId)
            ORDER BY h.holidayDate
           """)
    List<HrHoliday> findInRange(@Param("orgId") Long orgId,
                                @Param("branchId") Long branchId,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);

    @Query("""
           SELECT h FROM HrHoliday h
            WHERE h.pidOrg = :orgId
              AND h.status = 'Y'
              AND h.holidayYear = :year
              AND (h.pidBranch IS NULL OR h.pidBranch = :branchId)
            ORDER BY h.holidayDate
           """)
    List<HrHoliday> findForYear(@Param("orgId") Long orgId,
                                @Param("branchId") Long branchId,
                                @Param("year") Integer year);

    Optional<HrHoliday> findByPidHolidayAndPidOrg(Long pidHoliday, Long pidOrg);

    /**
     * Anything already on this date in the same scope.
     *
     * <p>Guards {@code uq_hr_holiday_date} with a sentence naming the existing
     * holiday, rather than letting a constraint name reach the user.
     */
    @Query("""
           SELECT h FROM HrHoliday h
            WHERE h.pidOrg = :orgId
              AND h.status = 'Y'
              AND h.holidayDate = :on
              AND ((:branchId IS NULL AND h.pidBranch IS NULL)
                   OR h.pidBranch = :branchId)
              AND (:excludeId IS NULL OR h.pidHoliday <> :excludeId)
           """)
    List<HrHoliday> findClashing(@Param("orgId") Long orgId,
                                 @Param("branchId") Long branchId,
                                 @Param("on") LocalDate on,
                                 @Param("excludeId") Long excludeId);
}
