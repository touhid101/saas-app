package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrDesignation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HrDesignationRepository extends JpaRepository<HrDesignation, Long> {
    List<HrDesignation> findByPidOrgAndStatusOrderByDesignationName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<HrDesignation> findByPidOrgAndPidBranchAndStatusOrderByDesignationName(Long pidOrg, Long pidBranch, String status);
}
