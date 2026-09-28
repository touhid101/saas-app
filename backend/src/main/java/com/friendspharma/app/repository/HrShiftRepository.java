package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Shifts, per branch.
 *
 * <p>A top-level interface, like every other repository here. Spring Data's
 * scanner does not register repository interfaces nested inside another class,
 * so grouping the three policy repositories into one file compiled cleanly and
 * then failed at startup with "required a bean that could not be found".
 */
@Repository
public interface HrShiftRepository extends JpaRepository<HrShift, Long> {

    List<HrShift> findByPidBranchOrderByStartTime(Long pidBranch);

    List<HrShift> findByPidBranchAndStatusOrderByStartTime(Long pidBranch, String status);

    Optional<HrShift> findByPidShiftAndPidBranch(Long pidShift, Long pidBranch);

    Optional<HrShift> findByPidBranchAndShiftCodeIgnoreCase(Long pidBranch, String shiftCode);

    boolean existsByPidBranchAndShiftCodeIgnoreCaseAndPidShiftNot(
            Long pidBranch, String shiftCode, Long excludeId);

    /**
     * How many people are on a shift.
     *
     * <p>Asked before retiring one: a shift forty staff are scheduled against is
     * a decision, not a tidy-up.
     */
    @Query("SELECT COUNT(e) FROM HrEmployee e WHERE e.pidShift = :id AND e.status = 'Y'")
    long countEmployeesOn(@Param("id") Long pidShift);
}
