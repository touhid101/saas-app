package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllDepartments;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllDepartmentsRepository extends JpaRepository<AllDepartments, Long> {
    List<AllDepartments> findByPidOrgAndStatusOrderByDepartmentName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<AllDepartments> findByPidOrgAndPidBranchAndStatusOrderByDepartmentName(Long pidOrg, Long pidBranch, String status);
}
