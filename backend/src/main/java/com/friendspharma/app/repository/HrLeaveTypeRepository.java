package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrLeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrLeaveTypeRepository extends JpaRepository<HrLeaveType, Long> {

    List<HrLeaveType> findByPidBranchOrderByTypeName(Long pidBranch);

    List<HrLeaveType> findByPidBranchAndStatusOrderByTypeName(Long pidBranch, String status);

    Optional<HrLeaveType> findByPidLeaveTypeAndPidBranch(Long pidLeaveType, Long pidBranch);

    Optional<HrLeaveType> findByPidBranchAndTypeCodeIgnoreCase(Long pidBranch, String typeCode);

    boolean existsByPidBranchAndTypeCodeIgnoreCaseAndPidLeaveTypeNot(
            Long pidBranch, String typeCode, Long excludeId);
}
