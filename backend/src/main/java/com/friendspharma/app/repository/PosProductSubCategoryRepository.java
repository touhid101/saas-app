package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosProductSubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosProductSubCategoryRepository extends JpaRepository<PosProductSubCategory, Long>, JpaSpecificationExecutor<PosProductSubCategory> {
    List<PosProductSubCategory> findByPidCategoryAndStatusOrderBySubCategoryName(Long pidCategory, String status);
    List<PosProductSubCategory> findByPidOrgAndStatusOrderBySubCategoryName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosProductSubCategory> findByPidOrgAndPidBranchAndStatusOrderBySubCategoryName(Long pidOrg, Long pidBranch, String status);
}
