package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrAttendance;
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
public interface HrAttendanceRepository extends JpaRepository<HrAttendance, Long> {

    /**
     * The duplicate check.
     *
     * <p>The service calls this first so the user gets a sentence rather than a
     * constraint name — but {@code uq_hr_attendance_day} is what makes the rule
     * true, because two devices posting the same punch at the same moment both
     * pass this check.
     */
    Optional<HrAttendance> findByPidEmployeeAndAttendanceDateAndStatus(
            Long pidEmployee, LocalDate attendanceDate, String status);

    /** One person's month, which is what payroll counts and what a profile shows. */
    List<HrAttendance> findByPidEmployeeAndAttendanceDateBetweenAndStatusOrderByAttendanceDate(
            Long pidEmployee, LocalDate from, LocalDate to, String status);

    /** One branch's day, which is what the attendance screen opens on. */
    @Query("""
           SELECT a FROM HrAttendance a
            WHERE a.pidBranch = :branchId
              AND a.attendanceDate = :date
              AND a.status = 'Y'
            ORDER BY a.pidEmployee
           """)
    List<HrAttendance> findBranchDay(@Param("branchId") Long branchId,
                                     @Param("date") LocalDate date);

    @Query("""
           SELECT a FROM HrAttendance a
            WHERE a.pidBranch = :branchId
              AND a.status = 'Y'
              AND a.attendanceDate BETWEEN :from AND :to
              AND (:employeeId IS NULL OR a.pidEmployee = :employeeId)
              AND (:attStatus = '' OR a.attendanceStatus = :attStatus)
            ORDER BY a.attendanceDate DESC, a.pidEmployee
           """)
    Page<HrAttendance> search(@Param("branchId") Long branchId,
                              @Param("employeeId") Long employeeId,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to,
                              @Param("attStatus") String attStatus,
                              Pageable pageable);

    /**
     * The whole branch for a date range, in one read.
     *
     * <p>Payroll needs every employee's month at once. Asking per employee
     * would be one query per payslip, which on a branch of forty staff is forty
     * round trips inside a single transaction.
     */
    @Query("""
           SELECT a FROM HrAttendance a
            WHERE a.pidBranch = :branchId
              AND a.status = 'Y'
              AND a.attendanceDate BETWEEN :from AND :to
            ORDER BY a.pidEmployee, a.attendanceDate
           """)
    List<HrAttendance> findBranchRange(@Param("branchId") Long branchId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    /**
     * Days with a correction waiting for a decision.
     *
     * <p>Backed by ix_hr_attendance_correction, the partial index V79 added for
     * exactly this read — the queue is small and the table is not.
     */
    @Query("""
           SELECT a FROM HrAttendance a
            WHERE a.pidBranch = :branchId
              AND a.status = 'Y'
              AND a.correctionStatus = 'REQUESTED'
            ORDER BY a.attendanceDate DESC
           """)
    List<HrAttendance> findPendingCorrections(@Param("branchId") Long branchId);

    long countByPidBranchAndCorrectionStatusAndStatus(Long pidBranch, String correctionStatus, String status);

    /** Rows a leave request created, so cancelling it removes exactly those. */
    List<HrAttendance> findByPidLeaveRequestAndStatus(Long pidLeaveRequest, String status);

    /** How many of each status at a branch on a day, for the screen summary. */
    @Query("""
           SELECT a.attendanceStatus, COUNT(a)
             FROM HrAttendance a
            WHERE a.pidBranch = :branchId AND a.attendanceDate = :date AND a.status = 'Y'
            GROUP BY a.attendanceStatus
           """)
    List<Object[]> countByStatusOn(@Param("branchId") Long branchId,
                                   @Param("date") LocalDate date);

    /**
     * One row for everyone the filter matches who has none for the day, in
     * one statement.
     *
     * <p>Written as SQL rather than as a loop of entity saves for one reason:
     * the loop cost four round trips a person, and at a branch of 2,500 that
     * was 111 seconds for a request a proxy gives up on after 60. This is one
     * round trip whatever the size of the branch.
     *
     * <p>It writes exactly what {@code apply()} writes for a status-only mark:
     * MANUAL source, zero minutes, no times, no note. Anything that would
     * need the entity rules — times, a leave check, an edit — still goes
     * through the entity path; this is only ever for people with no row.
     * The NOT EXISTS is what keeps {@code uq_hr_attendance_day} honest under
     * two people filling the same day at once.
     *
     * <p>{@code inserted_by} and {@code inserted_date} are set here because
     * JPA auditing only sees entity saves, and a fill that left them empty
     * would be the one write on the register nobody could attribute.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
           INSERT INTO PHARMA.HR_ATTENDANCE
                  (PID_ATTENDANCE, PID_ORG, PID_COMPANY, PID_BRANCH, PID_EMPLOYEE, ATTENDANCE_DATE,
                   ATTENDANCE_STATUS, SOURCE, WORKING_MINUTES, LATE_MINUTES, EARLY_LEAVE_MINUTES,
                   OVERTIME_MINUTES, STATUS, INSERTED_BY, INSERTED_DATE)
           SELECT nextval('PHARMA.SEQ_HR_ATTENDANCE'), e.PID_ORG, e.PID_COMPANY, e.PID_BRANCH, e.PID_EMPLOYEE,
                  CAST(:on AS DATE), :status, 'MANUAL', 0, 0, 0, 0, 'Y', :actor, now()
             FROM PHARMA.HR_EMPLOYEES e
            WHERE e.PID_BRANCH = :branchId
              AND e.STATUS = 'Y'
              AND (:q = ''
                   OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', CAST(:q AS TEXT), '%'))
                   OR LOWER(e.EMP_ID)   LIKE LOWER(CONCAT('%', CAST(:q AS TEXT), '%')))
              AND (CAST(:departmentId AS BIGINT) IS NULL OR e.PID_DEPARTMENT = CAST(:departmentId AS BIGINT))
              AND (CAST(:shiftId AS BIGINT) IS NULL OR e.PID_SHIFT = CAST(:shiftId AS BIGINT))
              AND NOT EXISTS (SELECT 1 FROM PHARMA.HR_ATTENDANCE a
                               WHERE a.PID_EMPLOYEE = e.PID_EMPLOYEE
                                 AND a.ATTENDANCE_DATE = CAST(:on AS DATE)
                                 AND a.STATUS = 'Y')
           """, nativeQuery = true)
    int fillDay(@Param("branchId") Long branchId,
                @Param("on") LocalDate on,
                @Param("status") String status,
                @Param("q") String q,
                @Param("departmentId") Long departmentId,
                @Param("shiftId") Long shiftId,
                @Param("actor") String actor);
}
