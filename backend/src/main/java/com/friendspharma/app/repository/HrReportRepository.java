package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrEmployee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * HR aggregates for the Reports screen.
 *
 * <p>Same conventions as {@link SalesPurchaseReportRepository}: native and
 * schema-qualified, every filter bound, and {@code ''} rather than null for "no
 * filter" — PostgreSQL cannot infer the type of a null parameter inside
 * {@code LOWER()} and settles on bytea.
 *
 * <p><b>Every query is branch-scoped and the branch is bound, never
 * interpolated.</b> {@code :branchId} arrives from
 * {@code BranchScopeService.resolve}, which refuses rather than widening when it
 * cannot decide — so a report can never quietly become an all-branches report.
 *
 * <p><b>Payroll figures are read, never recomputed.</b> Gross, deductions and
 * net come from {@code HR_PAYROLL_LINE} exactly as {@code PayrollCalculator}
 * wrote them. A report that re-derives a payslip is a second implementation of
 * the payroll arithmetic, and the first time it disagrees nobody can tell which
 * is right.
 */
@Repository
public interface HrReportRepository extends JpaRepository<HrEmployee, Long> {

    /* ══════════════════════════════════════════════════════════════════════
       PEOPLE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The employee register.
     *
     * <p>Inactive staff are included and marked: somebody who left in March is
     * part of the year's record, and a register that silently drops them cannot
     * be reconciled against a payroll run that paid them.
     */
    @Query(value = """
           SELECT e.EMP_ID,
                  e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '')  AS department,
                  COALESCE(g.DESIGNATION_NAME, '') AS designation,
                  e.EMP_TYPE,
                  COALESCE(e.EMPLOYMENT_TYPE, '')  AS employment_type,
                  e.HIRE_DATE,
                  COALESCE(e.PHONE, '')            AS phone,
                  e.BASIC_SALARY,
                  CASE WHEN e.STATUS = 'Y' THEN 'Active' ELSE 'Inactive' END AS state
             FROM PHARMA.HR_EMPLOYEES e
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT  = e.PID_DEPARTMENT
             LEFT JOIN PHARMA.HR_DESIGNATION  g ON g.PID_DESIGNATION = e.PID_DESIGNATION
            WHERE e.PID_ORG = :orgId
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(e.EMP_ID)   LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.STATUS DESC, e.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> employeeRegister(@Param("orgId") Long orgId,
                                    @Param("branchId") Long branchId,
                                    @Param("q") String q);

    /** Headcount by department and designation, active staff only. */
    @Query(value = """
           SELECT COALESCE(d.DEPARTMENT_NAME, '(none)')  AS department,
                  COALESCE(g.DESIGNATION_NAME, '(none)') AS designation,
                  COUNT(*)                                AS headcount,
                  COUNT(*) FILTER (WHERE e.EMPLOYMENT_TYPE = 'PERMANENT') AS permanent,
                  COUNT(*) FILTER (WHERE e.EMPLOYMENT_TYPE = 'PROBATION') AS probation,
                  COUNT(*) FILTER (WHERE e.BASIC_SALARY IS NULL
                                      OR e.PID_SALARY_STRUCTURE IS NULL)  AS not_payroll_ready,
                  COALESCE(SUM(e.BASIC_SALARY), 0)        AS basic_total
             FROM PHARMA.HR_EMPLOYEES e
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT  = e.PID_DEPARTMENT
             LEFT JOIN PHARMA.HR_DESIGNATION  g ON g.PID_DESIGNATION = e.PID_DESIGNATION
            WHERE e.PID_ORG = :orgId AND e.STATUS = 'Y'
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
            GROUP BY 1, 2
            ORDER BY 1, 2
           """, nativeQuery = true)
    List<Object[]> headcount(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /** Separations in a window. */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  s.SEPARATION_TYPE, s.NOTICE_DATE, s.LAST_WORKING_DATE,
                  s.SEPARATION_STATUS, s.SETTLEMENT_STATUS,
                  COALESCE(s.SETTLEMENT_AMOUNT, 0) AS settlement,
                  COALESCE(s.REASON, '') AS reason
             FROM PHARMA.HR_SEPARATION s
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = s.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE s.PID_ORG = :orgId AND s.STATUS = 'Y'
              AND (:branchId IS NULL OR s.PID_BRANCH = :branchId)
              AND s.LAST_WORKING_DATE BETWEEN :from AND :to
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY s.LAST_WORKING_DATE DESC
           """, nativeQuery = true)
    List<Object[]> separations(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                               @Param("from") LocalDate from, @Param("to") LocalDate to,
                               @Param("q") String q);

    /* ══════════════════════════════════════════════════════════════════════
       ATTENDANCE
       ══════════════════════════════════════════════════════════════════════ */

    /** One row per attendance record. The raw register. */
    @Query(value = """
           SELECT a.ATTENDANCE_DATE, e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  a.ATTENDANCE_STATUS,
                  a.CHECK_IN, a.CHECK_OUT,
                  COALESCE(a.WORKING_MINUTES, 0)     AS worked,
                  COALESCE(a.LATE_MINUTES, 0)        AS late,
                  COALESCE(a.EARLY_LEAVE_MINUTES, 0) AS early,
                  COALESCE(a.OVERTIME_MINUTES, 0)    AS overtime,
                  a.SOURCE
             FROM PHARMA.HR_ATTENDANCE a
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = a.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE a.PID_ORG = :orgId AND a.STATUS = 'Y'
              AND (:branchId IS NULL OR a.PID_BRANCH = :branchId)
              AND a.ATTENDANCE_DATE BETWEEN :from AND :to
              AND (:employeeId IS NULL OR a.PID_EMPLOYEE = :employeeId)
              AND (:attStatus = '' OR a.ATTENDANCE_STATUS = :attStatus)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY a.ATTENDANCE_DATE DESC, e.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> attendanceDetail(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                    @Param("from") LocalDate from, @Param("to") LocalDate to,
                                    @Param("employeeId") Long employeeId,
                                    @Param("attStatus") String attStatus,
                                    @Param("q") String q);

    /**
     * One row per employee for the window: the monthly summary.
     *
     * <p>{@code FILTER} rather than {@code SUM(CASE …)} because it says what it
     * means, and because the two are the same plan in PostgreSQL.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  COUNT(*) FILTER (WHERE a.ATTENDANCE_STATUS IN ('PRESENT','LATE','EARLY_LEAVE')) AS present,
                  COUNT(*) FILTER (WHERE a.ATTENDANCE_STATUS = 'HALF_DAY') AS half_day,
                  COUNT(*) FILTER (WHERE a.ATTENDANCE_STATUS = 'ABSENT')   AS absent,
                  COUNT(*) FILTER (WHERE a.ATTENDANCE_STATUS = 'LEAVE')    AS on_leave,
                  COUNT(*) FILTER (WHERE a.ATTENDANCE_STATUS = 'LATE')     AS late_days,
                  COALESCE(SUM(a.LATE_MINUTES), 0)     AS late_minutes,
                  COALESCE(SUM(a.OVERTIME_MINUTES), 0) AS overtime_minutes,
                  COALESCE(SUM(a.WORKING_MINUTES), 0)  AS worked_minutes
             FROM PHARMA.HR_ATTENDANCE a
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = a.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE a.PID_ORG = :orgId AND a.STATUS = 'Y'
              AND (:branchId IS NULL OR a.PID_BRANCH = :branchId)
              AND a.ATTENDANCE_DATE BETWEEN :from AND :to
              AND (:employeeId IS NULL OR a.PID_EMPLOYEE = :employeeId)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            GROUP BY e.PID_EMPLOYEE, e.EMP_ID, e.EMP_NAME, d.DEPARTMENT_NAME
            ORDER BY e.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> attendanceSummary(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                     @Param("from") LocalDate from, @Param("to") LocalDate to,
                                     @Param("employeeId") Long employeeId,
                                     @Param("q") String q);

    /**
     * Overtime, only where there is some.
     *
     * <p>Carries whether the employee's structure actually values it, so the
     * report can show hours that are recorded but unpaid as exactly that rather
     * than implying a cost the pharmacy has not agreed to.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  COUNT(*)                            AS days,
                  COALESCE(SUM(a.OVERTIME_MINUTES),0) AS minutes,
                  COALESCE(st.STRUCTURE_NAME, '')     AS structure,
                  st.OVERTIME_MULTIPLIER,
                  e.BASIC_SALARY,
                  st.STANDARD_HOURS_PER_DAY
             FROM PHARMA.HR_ATTENDANCE a
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = a.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
             LEFT JOIN PHARMA.HR_SALARY_STRUCTURE st ON st.PID_STRUCTURE = e.PID_SALARY_STRUCTURE
            WHERE a.PID_ORG = :orgId AND a.STATUS = 'Y'
              AND (:branchId IS NULL OR a.PID_BRANCH = :branchId)
              AND a.ATTENDANCE_DATE BETWEEN :from AND :to
              AND COALESCE(a.OVERTIME_MINUTES, 0) > 0
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            GROUP BY e.PID_EMPLOYEE, e.EMP_ID, e.EMP_NAME, d.DEPARTMENT_NAME,
                     st.STRUCTURE_NAME, st.OVERTIME_MULTIPLIER, e.BASIC_SALARY,
                     st.STANDARD_HOURS_PER_DAY
            ORDER BY minutes DESC
           """, nativeQuery = true)
    List<Object[]> overtime(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                            @Param("from") LocalDate from, @Param("to") LocalDate to,
                            @Param("q") String q);

    /**
     * The overtime claim workflow — who asked, what was granted, what reached
     * the register.
     *
     * <p>Deliberately separate from {@code overtime()}, which reports the
     * register itself. The register is what payroll values; this is the paper
     * trail behind part of it, and the two disagree wherever overtime was
     * entered directly on a day rather than claimed. That difference is the
     * report's whole reason to exist, so it is shown rather than reconciled
     * away.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  r.OT_DATE, r.CLAIMED_MINUTES,
                  COALESCE(r.APPROVED_MINUTES, 0) AS approved_minutes,
                  r.REQUEST_STATUS,
                  COALESCE(r.SUBMITTED_BY, '') AS submitted_by,
                  COALESCE(r.DECIDED_BY, '')   AS decided_by,
                  COALESCE(r.REASON, '')       AS reason
             FROM PHARMA.HR_OVERTIME_REQUEST r
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = r.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE r.PID_ORG = :orgId AND r.STATUS = 'Y'
              AND (:branchId IS NULL OR r.PID_BRANCH = :branchId)
              AND r.OT_DATE BETWEEN :from AND :to
              AND (:reqStatus = '' OR r.REQUEST_STATUS = :reqStatus)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY r.OT_DATE DESC, e.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> overtimeClaims(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to,
                                  @Param("reqStatus") String reqStatus, @Param("q") String q);

    /**
     * The increment register — the salary history.
     *
     * <p>The money columns are read exactly as they were frozen at approval.
     * Recomputing a percentage here would produce a figure nobody signed off,
     * and it would disagree with the one that was actually applied.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  i.INCREMENT_TYPE, i.INCREMENT_BASIS,
                  COALESCE(i.PCT_VALUE, 0)        AS pct_value,
                  COALESCE(i.PREVIOUS_SALARY, 0)  AS previous_salary,
                  COALESCE(i.INCREMENT_AMOUNT, 0) AS increment_amount,
                  COALESCE(i.NEW_SALARY, 0)       AS new_salary,
                  i.EFFECTIVE_DATE, i.INCREMENT_STATUS,
                  COALESCE(i.DECIDED_BY, '') AS decided_by,
                  COALESCE(i.REASON, '')     AS reason
             FROM PHARMA.HR_INCREMENT i
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = i.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE i.PID_ORG = :orgId AND i.STATUS = 'Y'
              AND (:branchId IS NULL OR i.PID_BRANCH = :branchId)
              AND i.EFFECTIVE_DATE BETWEEN :from AND :to
              AND (:incStatus = '' OR i.INCREMENT_STATUS = :incStatus)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY i.EFFECTIVE_DATE DESC, e.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> increments(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                              @Param("from") LocalDate from, @Param("to") LocalDate to,
                              @Param("incStatus") String incStatus, @Param("q") String q);

    /**
     * The provident fund: every movement in the window, with the balance each
     * person is left holding.
     *
     * <p>The balance is the whole fund to date rather than the window's, because
     * a fund balance that only counted the last month would be a number nobody
     * could reconcile against anything.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  l.ENTRY_DATE, l.ENTRY_TYPE,
                  COALESCE(l.BASE_AMOUNT, 0)     AS base_amount,
                  COALESCE(l.EMPLOYEE_PCT, 0)    AS employee_pct,
                  COALESCE(l.EMPLOYER_PCT, 0)    AS employer_pct,
                  l.EMPLOYEE_AMOUNT, l.EMPLOYER_AMOUNT,
                  CASE WHEN l.ENTRY_TYPE IN ('WITHDRAWAL','SETTLEMENT')
                       THEN -(l.EMPLOYEE_AMOUNT + l.EMPLOYER_AMOUNT)
                       ELSE  (l.EMPLOYEE_AMOUNT + l.EMPLOYER_AMOUNT) END AS movement,
                  COALESCE((SELECT SUM(CASE WHEN b.ENTRY_TYPE IN ('WITHDRAWAL','SETTLEMENT')
                                            THEN -(b.EMPLOYEE_AMOUNT + b.EMPLOYER_AMOUNT)
                                            ELSE  (b.EMPLOYEE_AMOUNT + b.EMPLOYER_AMOUNT) END)
                              FROM PHARMA.HR_PF_LEDGER b
                             WHERE b.PID_EMPLOYEE = l.PID_EMPLOYEE AND b.STATUS = 'Y'), 0) AS balance,
                  COALESCE(l.REMARKS, '') AS remarks
             FROM PHARMA.HR_PF_LEDGER l
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = l.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE l.PID_ORG = :orgId AND l.STATUS = 'Y'
              AND (:branchId IS NULL OR l.PID_BRANCH = :branchId)
              AND l.ENTRY_DATE BETWEEN :from AND :to
              AND (:employeeId IS NULL OR l.PID_EMPLOYEE = :employeeId)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.EMP_NAME, l.ENTRY_DATE, l.PID_PF_LEDGER
           """, nativeQuery = true)
    List<Object[]> providentFund(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to,
                                 @Param("employeeId") Long employeeId, @Param("q") String q);

    /**
     * Gratuity awards, with the working as it was frozen.
     *
     * <p>Read, never recomputed. Working the amount out again from today's
     * policy and today's salary would print a figure nobody signed off, and it
     * would disagree with what was paid.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  a.SERVICE_FROM, a.SERVICE_TO, a.SERVICE_YEARS,
                  a.WAGE_BASIS, a.MONTHLY_WAGE, a.DAILY_WAGE,
                  a.DAYS_PER_YEAR, a.DAYS_AWARDED, a.GRATUITY_AMOUNT,
                  a.AWARD_STATUS,
                  COALESCE(a.DECIDED_BY, '') AS decided_by,
                  a.PAID_DATE,
                  COALESCE(a.REASON, '') AS reason
             FROM PHARMA.HR_GRATUITY_AWARD a
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = a.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE a.PID_ORG = :orgId AND a.STATUS = 'Y'
              AND (:branchId IS NULL OR a.PID_BRANCH = :branchId)
              AND a.SERVICE_TO BETWEEN :from AND :to
              AND (:awardStatus = '' OR a.AWARD_STATUS = :awardStatus)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY a.SERVICE_TO DESC, e.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> gratuityAwards(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to,
                                  @Param("awardStatus") String awardStatus, @Param("q") String q);

    /**
     * Expense and conveyance claims, one row per line.
     *
     * <p>Line by line rather than claim by claim, because the question this
     * report is opened to answer is "what are we spending on conveyance" and a
     * claim total mixes the fares with the dinner.
     *
     * <p>The approved column is the figure allowed on that line, and it is null
     * until the claim is decided — shown as blank rather than as the claimed
     * amount, because a claim awaiting a decision has not been allowed anything.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  t.TYPE_NAME,
                  l.EXPENSE_DATE,
                  COALESCE(NULLIF(TRIM(CONCAT_WS(' to ', l.FROM_PLACE, l.TO_PLACE)), ''),
                           COALESCE(l.DESCRIPTION, '')) AS detail,
                  l.CLAIMED_AMOUNT,
                  l.APPROVED_AMOUNT,
                  c.CLAIM_STATUS,
                  CASE WHEN l.RECEIPT_PATH IS NULL OR l.RECEIPT_PATH = '' THEN 'No' ELSE 'Yes' END
                       AS receipt,
                  c.PAID_DATE,
                  COALESCE(c.DECIDED_BY, '') AS decided_by
             FROM PHARMA.HR_EXPENSE_CLAIM_LINE l
             JOIN PHARMA.HR_EXPENSE_CLAIM c ON c.PID_EXPENSE_CLAIM = l.PID_EXPENSE_CLAIM
             JOIN PHARMA.HR_EXPENSE_TYPE  t ON t.PID_EXPENSE_TYPE  = l.PID_EXPENSE_TYPE
             JOIN PHARMA.HR_EMPLOYEES     e ON e.PID_EMPLOYEE      = c.PID_EMPLOYEE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE c.PID_ORG = :orgId AND c.STATUS = 'Y' AND l.STATUS = 'Y'
              AND (:branchId IS NULL OR c.PID_BRANCH = :branchId)
              AND l.EXPENSE_DATE BETWEEN :from AND :to
              AND (:employeeId IS NULL OR c.PID_EMPLOYEE = :employeeId)
              AND (:claimStatus = '' OR c.CLAIM_STATUS = :claimStatus)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY l.EXPENSE_DATE DESC, e.EMP_NAME, t.TYPE_NAME
           """, nativeQuery = true)
    List<Object[]> expenseClaims(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to,
                                 @Param("employeeId") Long employeeId,
                                 @Param("claimStatus") String claimStatus, @Param("q") String q);

    /* ══════════════════════════════════════════════════════════════════════
       LEAVE
       ══════════════════════════════════════════════════════════════════════ */

    /** Every request overlapping the window, whatever its state. */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  t.TYPE_NAME,
                  CASE WHEN t.IS_PAID = 1 THEN 'Paid' ELSE 'Unpaid' END AS paid,
                  r.FROM_DATE, r.TO_DATE, r.DAYS, r.REQUEST_STATUS,
                  COALESCE(r.DECIDED_BY, '') AS decided_by,
                  COALESCE(r.REASON, '')     AS reason
             FROM PHARMA.HR_LEAVE_REQUEST r
             JOIN PHARMA.HR_EMPLOYEES  e ON e.PID_EMPLOYEE   = r.PID_EMPLOYEE
             JOIN PHARMA.HR_LEAVE_TYPE t ON t.PID_LEAVE_TYPE = r.PID_LEAVE_TYPE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE r.PID_ORG = :orgId AND r.STATUS = 'Y'
              AND (:branchId IS NULL OR r.PID_BRANCH = :branchId)
              AND r.FROM_DATE <= :to AND r.TO_DATE >= :from
              AND (:employeeId IS NULL OR r.PID_EMPLOYEE = :employeeId)
              AND (:reqStatus = '' OR r.REQUEST_STATUS = :reqStatus)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY r.FROM_DATE DESC
           """, nativeQuery = true)
    List<Object[]> leaveRegister(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to,
                                 @Param("employeeId") Long employeeId,
                                 @Param("reqStatus") String reqStatus,
                                 @Param("q") String q);

    /**
     * Balances as they stand, for a leave year.
     *
     * <p>Available is computed here the same way the entity computes it —
     * entitled plus carried, less taken and pending — rather than being read
     * from a stored column, because there is no stored column: a fourth number
     * that could disagree with the other three.
     */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  t.TYPE_NAME,
                  b.ENTITLED, b.CARRIED_FORWARD, b.TAKEN, b.PENDING,
                  (b.ENTITLED + b.CARRIED_FORWARD - b.TAKEN - b.PENDING) AS available,
                  CASE WHEN (b.ENTITLED + b.CARRIED_FORWARD) = 0 THEN NULL
                       ELSE ROUND(b.TAKEN * 100.0 / (b.ENTITLED + b.CARRIED_FORWARD), 1)
                  END AS used_pct
             FROM PHARMA.HR_LEAVE_BALANCE b
             JOIN PHARMA.HR_EMPLOYEES  e ON e.PID_EMPLOYEE   = b.PID_EMPLOYEE
             JOIN PHARMA.HR_LEAVE_TYPE t ON t.PID_LEAVE_TYPE = b.PID_LEAVE_TYPE
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = e.PID_DEPARTMENT
            WHERE b.PID_ORG = :orgId AND b.STATUS = 'Y'
              AND (:branchId IS NULL OR b.PID_BRANCH = :branchId)
              AND b.LEAVE_YEAR = :year
              AND (:employeeId IS NULL OR b.PID_EMPLOYEE = :employeeId)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.EMP_NAME, t.TYPE_NAME
           """, nativeQuery = true)
    List<Object[]> leaveBalance(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                @Param("year") Integer year,
                                @Param("employeeId") Long employeeId,
                                @Param("q") String q);

    /** Days approved per type in the window — what leave is actually used for. */
    @Query(value = """
           SELECT t.TYPE_NAME,
                  CASE WHEN t.IS_PAID = 1 THEN 'Paid' ELSE 'Unpaid' END AS paid,
                  COUNT(*)                          AS requests,
                  COUNT(DISTINCT r.PID_EMPLOYEE)    AS employees,
                  COALESCE(SUM(r.DAYS), 0)          AS days
             FROM PHARMA.HR_LEAVE_REQUEST r
             JOIN PHARMA.HR_LEAVE_TYPE t ON t.PID_LEAVE_TYPE = r.PID_LEAVE_TYPE
            WHERE r.PID_ORG = :orgId AND r.STATUS = 'Y'
              AND r.REQUEST_STATUS = 'APPROVED'
              AND (:branchId IS NULL OR r.PID_BRANCH = :branchId)
              AND r.FROM_DATE <= :to AND r.TO_DATE >= :from
            GROUP BY t.TYPE_NAME, t.IS_PAID
            ORDER BY days DESC
           """, nativeQuery = true)
    List<Object[]> leaveUtilisation(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                    @Param("from") LocalDate from, @Param("to") LocalDate to);

    /* ══════════════════════════════════════════════════════════════════════
       MONEY LENT
       ══════════════════════════════════════════════════════════════════════ */

    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  a.REQUEST_DATE, a.AMOUNT, COALESCE(a.APPROVED_AMOUNT, 0) AS approved,
                  a.RECOVERED_AMOUNT,
                  GREATEST(COALESCE(a.APPROVED_AMOUNT,0) - COALESCE(a.RECOVERED_AMOUNT,0), 0) AS outstanding,
                  a.INSTALLMENTS, a.ADVANCE_STATUS, a.PAID_DATE
             FROM PHARMA.HR_ADVANCE a
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = a.PID_EMPLOYEE
            WHERE a.PID_ORG = :orgId AND a.STATUS = 'Y'
              AND (:branchId IS NULL OR a.PID_BRANCH = :branchId)
              AND a.REQUEST_DATE BETWEEN :from AND :to
              AND (:employeeId IS NULL OR a.PID_EMPLOYEE = :employeeId)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY a.REQUEST_DATE DESC
           """, nativeQuery = true)
    List<Object[]> advanceRegister(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("employeeId") Long employeeId,
                                   @Param("q") String q);

    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  l.REQUEST_DATE, l.PRINCIPAL, COALESCE(l.APPROVED_PRINCIPAL,0) AS approved,
                  l.INSTALLMENTS, COALESCE(l.INSTALLMENT_AMOUNT,0) AS per_month,
                  l.RECOVERED_AMOUNT,
                  GREATEST(COALESCE(l.APPROVED_PRINCIPAL,0) - COALESCE(l.RECOVERED_AMOUNT,0), 0) AS outstanding,
                  (SELECT COUNT(*) FROM PHARMA.HR_LOAN_SCHEDULE s
                    WHERE s.PID_LOAN = l.PID_LOAN AND s.SCHEDULE_STATUS = 'RECOVERED') AS paid_installments,
                  l.LOAN_STATUS, l.DISBURSED_DATE
             FROM PHARMA.HR_LOAN l
             JOIN PHARMA.HR_EMPLOYEES e ON e.PID_EMPLOYEE = l.PID_EMPLOYEE
            WHERE l.PID_ORG = :orgId AND l.STATUS = 'Y'
              AND (:branchId IS NULL OR l.PID_BRANCH = :branchId)
              AND l.REQUEST_DATE BETWEEN :from AND :to
              AND (:employeeId IS NULL OR l.PID_EMPLOYEE = :employeeId)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY l.REQUEST_DATE DESC
           """, nativeQuery = true)
    List<Object[]> loanRegister(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                @Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("employeeId") Long employeeId,
                                @Param("q") String q);

    /* ══════════════════════════════════════════════════════════════════════
       SALARY AND PAYROLL
       ══════════════════════════════════════════════════════════════════════ */

    /** What people are on now — the pay basis, not a payroll run. */
    @Query(value = """
           SELECT e.EMP_ID, e.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '')  AS department,
                  COALESCE(g.DESIGNATION_NAME, '') AS designation,
                  COALESCE(st.STRUCTURE_NAME, '')  AS structure,
                  e.BASIC_SALARY,
                  COALESCE(e.SALARY_PAYMENT_MODE, '') AS pay_mode,
                  COALESCE(e.BANK_NAME, '')           AS bank,
                  CASE WHEN e.BASIC_SALARY IS NULL OR e.PID_SALARY_STRUCTURE IS NULL
                       THEN 'Not ready' ELSE 'Ready' END AS payroll_ready
             FROM PHARMA.HR_EMPLOYEES e
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT  = e.PID_DEPARTMENT
             LEFT JOIN PHARMA.HR_DESIGNATION  g ON g.PID_DESIGNATION = e.PID_DESIGNATION
             LEFT JOIN PHARMA.HR_SALARY_STRUCTURE st ON st.PID_STRUCTURE = e.PID_SALARY_STRUCTURE
            WHERE e.PID_ORG = :orgId AND e.STATUS = 'Y'
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
              AND (:q = '' OR LOWER(e.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> salaryRegister(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                  @Param("q") String q);

    /**
     * The payroll register: one row per payslip.
     *
     * <p>Cancelled runs are excluded. A run somebody abandoned is evidence that
     * it happened, not a salary anybody was paid.
     */
    @Query(value = """
           SELECT p.PAYROLL_NO, pr.PERIOD_YEAR, pr.PERIOD_MONTH,
                  l.EMP_ID, l.EMP_NAME,
                  COALESCE(d.DEPARTMENT_NAME, '') AS department,
                  l.BASIC_SALARY, l.ALLOWANCE_TOTAL, l.OVERTIME_AMOUNT, l.BONUS_AMOUNT,
                  l.GROSS_SALARY, l.DEDUCTION_TOTAL, l.NET_SALARY,
                  l.PAYMENT_STATUS, l.PAID_AMOUNT
             FROM PHARMA.HR_PAYROLL_LINE l
             JOIN PHARMA.HR_PAYROLL        p  ON p.PID_PAYROLL = l.PID_PAYROLL
             JOIN PHARMA.HR_PAYROLL_PERIOD pr ON pr.PID_PERIOD = p.PID_PERIOD
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = l.PID_DEPARTMENT
            WHERE p.PID_ORG = :orgId AND l.STATUS = 'Y'
              AND p.PAYROLL_STATUS <> 'CANCELLED'
              AND (:branchId IS NULL OR p.PID_BRANCH = :branchId)
              AND pr.PERIOD_TO BETWEEN :from AND :to
              AND (:employeeId IS NULL OR l.PID_EMPLOYEE = :employeeId)
              AND (:q = '' OR LOWER(l.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY pr.PERIOD_YEAR DESC, pr.PERIOD_MONTH DESC, l.EMP_NAME
           """, nativeQuery = true)
    List<Object[]> payrollRegister(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("employeeId") Long employeeId,
                                   @Param("q") String q);

    /** One row per run. */
    @Query(value = """
           SELECT p.PAYROLL_NO, pr.PERIOD_YEAR, pr.PERIOD_MONTH,
                  p.PAYROLL_STATUS, p.EMPLOYEE_COUNT,
                  p.TOTAL_GROSS, p.TOTAL_DEDUCTION, p.TOTAL_TAX, p.TOTAL_NET, p.TOTAL_PAID,
                  GREATEST(COALESCE(p.TOTAL_NET,0) - COALESCE(p.TOTAL_PAID,0), 0) AS unpaid,
                  COALESCE(p.APPROVED_BY, '') AS approved_by, p.POSTED_AT
             FROM PHARMA.HR_PAYROLL p
             JOIN PHARMA.HR_PAYROLL_PERIOD pr ON pr.PID_PERIOD = p.PID_PERIOD
            WHERE p.PID_ORG = :orgId AND p.STATUS = 'Y'
              AND (:branchId IS NULL OR p.PID_BRANCH = :branchId)
              AND pr.PERIOD_TO BETWEEN :from AND :to
            ORDER BY pr.PERIOD_YEAR DESC, pr.PERIOD_MONTH DESC
           """, nativeQuery = true)
    List<Object[]> payrollSummary(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Cost by department, for the runs in the window. */
    @Query(value = """
           SELECT COALESCE(d.DEPARTMENT_NAME, '(none)') AS department,
                  COUNT(*)                     AS payslips,
                  SUM(l.BASIC_SALARY)          AS basic,
                  SUM(l.ALLOWANCE_TOTAL)       AS allowances,
                  SUM(l.OVERTIME_AMOUNT)       AS overtime,
                  SUM(l.BONUS_AMOUNT)          AS bonus,
                  SUM(l.GROSS_SALARY)          AS gross,
                  SUM(l.DEDUCTION_TOTAL)       AS deductions,
                  SUM(l.NET_SALARY)            AS net
             FROM PHARMA.HR_PAYROLL_LINE l
             JOIN PHARMA.HR_PAYROLL        p  ON p.PID_PAYROLL = l.PID_PAYROLL
             JOIN PHARMA.HR_PAYROLL_PERIOD pr ON pr.PID_PERIOD = p.PID_PERIOD
             LEFT JOIN PHARMA.ALL_DEPARTMENTS d ON d.PID_DEPARTMENT = l.PID_DEPARTMENT
            WHERE p.PID_ORG = :orgId AND l.STATUS = 'Y'
              AND p.PAYROLL_STATUS <> 'CANCELLED'
              AND (:branchId IS NULL OR p.PID_BRANCH = :branchId)
              AND pr.PERIOD_TO BETWEEN :from AND :to
            GROUP BY 1
            ORDER BY gross DESC
           """, nativeQuery = true)
    List<Object[]> payrollCost(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** What was deducted, and why. */
    @Query(value = """
           SELECT l.EMP_ID, l.EMP_NAME, p.PAYROLL_NO,
                  l.ABSENCE_DEDUCTION, l.ADVANCE_RECOVERY, l.LOAN_RECOVERY, l.TAX_AMOUNT,
                  GREATEST(COALESCE(l.DEDUCTION_TOTAL,0)
                           - COALESCE(l.ABSENCE_DEDUCTION,0)
                           - COALESCE(l.ADVANCE_RECOVERY,0)
                           - COALESCE(l.LOAN_RECOVERY,0)
                           - COALESCE(l.TAX_AMOUNT,0), 0) AS other_deductions,
                  l.DEDUCTION_TOTAL, l.NET_SALARY
             FROM PHARMA.HR_PAYROLL_LINE l
             JOIN PHARMA.HR_PAYROLL        p  ON p.PID_PAYROLL = l.PID_PAYROLL
             JOIN PHARMA.HR_PAYROLL_PERIOD pr ON pr.PID_PERIOD = p.PID_PERIOD
            WHERE p.PID_ORG = :orgId AND l.STATUS = 'Y'
              AND p.PAYROLL_STATUS <> 'CANCELLED'
              AND (:branchId IS NULL OR p.PID_BRANCH = :branchId)
              AND pr.PERIOD_TO BETWEEN :from AND :to
              AND COALESCE(l.DEDUCTION_TOTAL, 0) > 0
              AND (:q = '' OR LOWER(l.EMP_NAME) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY l.DEDUCTION_TOTAL DESC
           """, nativeQuery = true)
    List<Object[]> payrollDeductions(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                     @Param("from") LocalDate from, @Param("to") LocalDate to,
                                     @Param("q") String q);

    /**
     * Everything that would stop a payroll being right, before it is approved.
     *
     * <p>Four separate faults, unioned so the screen shows one list rather than
     * four. Severity is decided here rather than in Java because it is a
     * property of the fault, not of the caller: an employee with no salary is an
     * ERROR whoever is looking.
     */
    @Query(value = """
           SELECT 'ERROR' AS severity, e.EMP_ID, e.EMP_NAME,
                  'No basic salary set' AS issue,
                  'Payroll will skip this employee entirely' AS effect
             FROM PHARMA.HR_EMPLOYEES e
            WHERE e.PID_ORG = :orgId AND e.STATUS = 'Y'
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
              AND (e.BASIC_SALARY IS NULL OR e.BASIC_SALARY <= 0)
           UNION ALL
           SELECT 'ERROR', e.EMP_ID, e.EMP_NAME,
                  'No salary structure assigned',
                  'Payroll will skip this employee entirely'
             FROM PHARMA.HR_EMPLOYEES e
            WHERE e.PID_ORG = :orgId AND e.STATUS = 'Y'
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
              AND e.PID_SALARY_STRUCTURE IS NULL
              AND e.BASIC_SALARY IS NOT NULL AND e.BASIC_SALARY > 0
           UNION ALL
           SELECT 'ERROR', e.EMP_ID, e.EMP_NAME,
                  'Paid by bank with no account number',
                  'The payment cannot be made'
             FROM PHARMA.HR_EMPLOYEES e
            WHERE e.PID_ORG = :orgId AND e.STATUS = 'Y'
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
              AND e.SALARY_PAYMENT_MODE = 'BANK'
              AND (e.BANK_ACCOUNT_NO IS NULL OR e.BANK_ACCOUNT_NO = '')
           UNION ALL
           SELECT 'WARNING', e.EMP_ID, e.EMP_NAME,
                  'No attendance recorded in the period',
                  'The whole period counts as absent'
             FROM PHARMA.HR_EMPLOYEES e
            WHERE e.PID_ORG = :orgId AND e.STATUS = 'Y'
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
              AND e.BASIC_SALARY IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM PHARMA.HR_ATTENDANCE a
                               WHERE a.PID_EMPLOYEE = e.PID_EMPLOYEE
                                 AND a.STATUS = 'Y'
                                 AND a.ATTENDANCE_DATE BETWEEN :from AND :to)
           UNION ALL
           SELECT 'WARNING', e.EMP_ID, e.EMP_NAME,
                  'Leave request still awaiting a decision in this period',
                  'Approve or reject it before calculating, or the days count as worked'
             FROM PHARMA.HR_EMPLOYEES e
            WHERE e.PID_ORG = :orgId AND e.STATUS = 'Y'
              AND (:branchId IS NULL OR e.PID_BRANCH = :branchId)
              AND EXISTS (SELECT 1 FROM PHARMA.HR_LEAVE_REQUEST r
                           WHERE r.PID_EMPLOYEE = e.PID_EMPLOYEE
                             AND r.STATUS = 'Y' AND r.REQUEST_STATUS = 'SUBMITTED'
                             AND r.FROM_DATE <= :to AND r.TO_DATE >= :from)
            ORDER BY 1, 3
           """, nativeQuery = true)
    List<Object[]> payrollExceptions(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                     @Param("from") LocalDate from, @Param("to") LocalDate to);
}
