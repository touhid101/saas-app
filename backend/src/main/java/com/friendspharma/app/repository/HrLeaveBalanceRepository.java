package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrLeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrLeaveBalanceRepository extends JpaRepository<HrLeaveBalance, Long> {

    Optional<HrLeaveBalance> findByPidEmployeeAndPidLeaveTypeAndLeaveYear(
            Long pidEmployee, Long pidLeaveType, Integer leaveYear);

    List<HrLeaveBalance> findByPidEmployeeAndLeaveYearOrderByPidLeaveType(
            Long pidEmployee, Integer leaveYear);

    List<HrLeaveBalance> findByPidBranchAndLeaveYear(Long pidBranch, Integer leaveYear);

    /**
     * Locks the balance row before it is adjusted.
     *
     * <p>Two requests approved for one employee at the same moment both read
     * the same available figure and both pass the check. Reading the row under
     * a lock, then changing it, makes the check and the change one step — the
     * same shape {@code DocumentNumberService} uses to hand out a number.
     */
    @Query(value = """
           SELECT * FROM PHARMA.HR_LEAVE_BALANCE
            WHERE PID_EMPLOYEE = :employeeId
              AND PID_LEAVE_TYPE = :typeId
              AND LEAVE_YEAR = :year
            FOR UPDATE
           """, nativeQuery = true)
    Optional<HrLeaveBalance> lockFor(@Param("employeeId") Long employeeId,
                                     @Param("typeId") Long typeId,
                                     @Param("year") Integer year);
}
