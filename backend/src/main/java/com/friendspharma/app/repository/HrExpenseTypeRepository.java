package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrExpenseType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** The kinds of expense a branch reimburses. */
@Repository
public interface HrExpenseTypeRepository extends JpaRepository<HrExpenseType, Long> {

    List<HrExpenseType> findByPidBranchAndStatusOrderBySortOrderAscTypeNameAsc(
            Long pidBranch, String status);

    List<HrExpenseType> findByPidBranchOrderBySortOrderAscTypeNameAsc(Long pidBranch);

    Optional<HrExpenseType> findByPidExpenseTypeAndPidBranch(Long id, Long pidBranch);

    boolean existsByPidBranchAndTypeCodeIgnoreCaseAndStatus(
            Long pidBranch, String typeCode, String status);

    boolean existsByPidBranchAndTypeCodeIgnoreCaseAndPidExpenseTypeNotAndStatus(
            Long pidBranch, String typeCode, Long excludeId, String status);
}
