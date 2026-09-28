package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrLoan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrLoanRepository extends JpaRepository<HrLoan, Long> {

    Optional<HrLoan> findByPidLoanAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT l FROM HrLoan l
            WHERE l.pidBranch = :branchId
              AND l.status = 'Y'
              AND (:employeeId IS NULL OR l.pidEmployee = :employeeId)
              AND (:loanStatus = '' OR l.loanStatus = :loanStatus)
            ORDER BY l.requestDate DESC, l.pidLoan DESC
           """)
    Page<HrLoan> search(@Param("branchId") Long branchId,
                        @Param("employeeId") Long employeeId,
                        @Param("loanStatus") String loanStatus,
                        Pageable pageable);

    /** Loans payroll should be recovering, for the whole branch, read once. */
    @Query("""
           SELECT l FROM HrLoan l
            WHERE l.pidBranch = :branchId
              AND l.status = 'Y'
              AND l.loanStatus IN ('DISBURSED','RECOVERING')
            ORDER BY l.pidEmployee, l.requestDate
           """)
    List<HrLoan> findRecoverable(@Param("branchId") Long branchId);

    List<HrLoan> findByPidEmployeeAndStatusOrderByRequestDateDesc(Long pidEmployee, String status);

    long countByPidBranchAndLoanStatusAndStatus(Long pidBranch, String loanStatus, String status);

    /** The approval queue, read by the unified inbox. */
    List<HrLoan> findByPidBranchAndLoanStatusAndStatusOrderByRequestDate(
            Long pidBranch, String loanStatus, String status);

}
