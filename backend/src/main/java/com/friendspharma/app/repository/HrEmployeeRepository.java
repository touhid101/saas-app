package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrEmployee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrEmployeeRepository extends JpaRepository<HrEmployee, Long>, JpaSpecificationExecutor<HrEmployee> {

    List<HrEmployee> findByPidOrgAndStatusOrderByEmpName(Long pidOrg, String status);
    List<HrEmployee> findByPidBranchAndStatusOrderByEmpName(Long pidBranch, String status);

    /** EMP_ID is the human-facing code; used to reject duplicates on create. */
    Optional<HrEmployee> findByPidOrgAndEmpIdIgnoreCase(Long orgId, String empId);
    boolean existsByPidOrgAndEmpIdIgnoreCaseAndPidEmployeeNot(Long orgId, String empId, Long excludeId);

    /**
     * Active delivery men, optionally narrowed to one branch.
     *
     * <p>The type filter is the point. The query this replaced matched on org
     * and status alone, so every active employee came back as a delivery man
     * and an invoice could be dispatched to whoever happened to be on the list.
     *
     * <p>Branch is optional because a delivery man may cover more than one:
     * passing null means "anyone in the org", which is what a head-office user
     * assigning across branches needs.
     */
    @Query("""
           SELECT e FROM HrEmployee e
            WHERE e.pidOrg  = :orgId
              AND e.status  = 'Y'
              AND e.empType = 'DELIVERY_MAN'
              AND (:branchId IS NULL OR e.pidBranch = :branchId)
              AND (:q = ''
                   OR LOWER(e.empName)   LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.empId)     LIKE LOWER(CONCAT('%', :q, '%'))
                   OR e.phone            LIKE CONCAT('%', :q, '%')
                   OR LOWER(e.vehicleNo) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.empName
           """)
    List<HrEmployee> findDeliveryMen(@Param("orgId") Long orgId,
                                     @Param("branchId") Long branchId,
                                     @Param("q") String q);

    /**
     * The employee list behind the HR screen.
     *
     * <p>Unlike {@link #findDeliveryMen} this returns inactive rows too — an
     * employee who has left still has to be visible to be re-activated, and
     * hiding them would make the list disagree with the database.
     */
    @Query("""
           SELECT e FROM HrEmployee e
            WHERE e.pidOrg = :orgId
              AND (:branchId IS NULL OR e.pidBranch = :branchId)
              AND (:type   = '' OR e.empType = :type)
              AND (:status = '' OR e.status  = :status)
              AND (:q = ''
                   OR LOWER(e.empName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.empId)   LIKE LOWER(CONCAT('%', :q, '%'))
                   OR e.phone          LIKE CONCAT('%', :q, '%'))
            ORDER BY e.empName
           """)
    Page<HrEmployee> search(@Param("orgId") Long orgId,
                            @Param("branchId") Long branchId,
                            @Param("type") String type,
                            @Param("status") String status,
                            @Param("q") String q,
                            Pageable pageable);

    /**
     * One page of the register for one day.
     *
     * <p>The register lists <b>people</b>, not attendance rows: the ones with
     * no row yet are the point of the screen at nine in the morning. So this
     * pages over employees and left-joins the day's row, which lets the filter
     * say "only the unrecorded" — the question the register exists to answer —
     * as well as "only the absent".
     *
     * <p>Paged because a branch of 2,500 is a 2,500-row response on every date
     * change otherwise, and nobody reads a register that long in one screen.
     * The counts for the day come from {@code countByStatusOn}, over the whole
     * branch, so the tiles above the page describe the day and not the page.
     *
     * @param filter '' for everyone, NOT_RECORDED for people with no row, or an
     *               attendance status for people whose row says that
     */
    @Query(value = """
           SELECT e FROM HrEmployee e
             LEFT JOIN HrAttendance a
                    ON a.pidEmployee = e.pidEmployee
                   AND a.attendanceDate = :on
                   AND a.status = 'Y'
            WHERE e.pidBranch = :branchId
              AND e.status = 'Y'
              AND (:q = ''
                   OR LOWER(e.empName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.empId)   LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:departmentId IS NULL OR e.pidDepartment = :departmentId)
              AND (:shiftId      IS NULL OR e.pidShift      = :shiftId)
              AND (:filter = ''
                   OR (:filter = 'NOT_RECORDED' AND a.pidAttendance IS NULL)
                   OR a.attendanceStatus = :filter)
            ORDER BY e.empName, e.pidEmployee
           """,
           countQuery = """
           SELECT COUNT(e) FROM HrEmployee e
             LEFT JOIN HrAttendance a
                    ON a.pidEmployee = e.pidEmployee
                   AND a.attendanceDate = :on
                   AND a.status = 'Y'
            WHERE e.pidBranch = :branchId
              AND e.status = 'Y'
              AND (:q = ''
                   OR LOWER(e.empName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.empId)   LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:departmentId IS NULL OR e.pidDepartment = :departmentId)
              AND (:shiftId      IS NULL OR e.pidShift      = :shiftId)
              AND (:filter = ''
                   OR (:filter = 'NOT_RECORDED' AND a.pidAttendance IS NULL)
                   OR a.attendanceStatus = :filter)
           """)
    Page<HrEmployee> registerPage(@Param("branchId") Long branchId,
                                  @Param("on") java.time.LocalDate on,
                                  @Param("q") String q,
                                  @Param("departmentId") Long departmentId,
                                  @Param("shiftId") Long shiftId,
                                  @Param("filter") String filter,
                                  Pageable pageable);

    /** The whole branch, as one number for the register's progress bar. */
    long countByPidBranchAndStatus(Long pidBranch, String status);

    /** How many of each type, for the HR screen's summary. */
    @Query("""
           SELECT e.empType, COUNT(e)
             FROM HrEmployee e
            WHERE e.pidOrg = :orgId AND e.status = 'Y'
              AND (:branchId IS NULL OR e.pidBranch = :branchId)
            GROUP BY e.empType
           """)
    List<Object[]> countByType(@Param("orgId") Long orgId,
                               @Param("branchId") Long branchId);
}
