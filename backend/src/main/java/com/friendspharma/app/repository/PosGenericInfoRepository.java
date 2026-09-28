package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosGenericInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosGenericInfoRepository extends JpaRepository<PosGenericInfo, Long>, JpaSpecificationExecutor<PosGenericInfo> {
    List<PosGenericInfo> findByPidOrgAndStatusOrderByGenericName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosGenericInfo> findByPidOrgAndPidBranchAndStatusOrderByGenericName(Long pidOrg, Long pidBranch, String status);
}
