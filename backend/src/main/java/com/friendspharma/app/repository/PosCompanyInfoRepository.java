package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosCompanyInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosCompanyInfoRepository extends JpaRepository<PosCompanyInfo, Long>, JpaSpecificationExecutor<PosCompanyInfo> {
    List<PosCompanyInfo> findByPidOrgAndStatusOrderByCompanyName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosCompanyInfo> findByPidOrgAndPidBranchAndStatusOrderByCompanyName(Long pidOrg, Long pidBranch, String status);
}
