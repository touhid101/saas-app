package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrSalaryStructure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrSalaryStructureRepository extends JpaRepository<HrSalaryStructure, Long> {

    /**
     * Every structure at a branch, active and not.
     *
     * <p>Inactive rows are included because a structure that has been retired
     * still values the payslips already issued against it, and a list that
     * hides them makes those payslips look as though they came from nowhere.
     */
    List<HrSalaryStructure> findByPidBranchOrderByStructureName(Long pidBranch);

    List<HrSalaryStructure> findByPidBranchAndStatusOrderByStructureName(Long pidBranch, String status);

    Optional<HrSalaryStructure> findByPidStructureAndPidBranch(Long pidStructure, Long pidBranch);

    /** Rejects a second structure with the same name at one branch. */
    boolean existsByPidBranchAndStructureNameIgnoreCase(Long pidBranch, String structureName);

    boolean existsByPidBranchAndStructureNameIgnoreCaseAndPidStructureNot(
            Long pidBranch, String structureName, Long excludeId);

    /**
     * How many employees a structure is attached to.
     *
     * <p>Asked before deactivating one: retiring a structure that thirty people
     * are on is a decision, not a tidy-up, and the screen says so.
     */
    @Query("SELECT COUNT(e) FROM HrEmployee e WHERE e.pidSalaryStructure = :id AND e.status = 'Y'")
    long countEmployeesOn(@Param("id") Long pidStructure);
}
