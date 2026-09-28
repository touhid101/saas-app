package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosProductCategoryRepository extends JpaRepository<PosProductCategory, Long>, JpaSpecificationExecutor<PosProductCategory> {
    List<PosProductCategory> findByPidOrgAndStatusOrderByCategoryName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosProductCategory> findByPidOrgAndPidBranchAndStatusOrderByCategoryName(Long pidOrg, Long pidBranch, String status);
}
