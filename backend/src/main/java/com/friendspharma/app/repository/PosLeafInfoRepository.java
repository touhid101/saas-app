package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosLeafInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosLeafInfoRepository extends JpaRepository<PosLeafInfo, Long> {
    List<PosLeafInfo> findByPidOrgAndStatusOrderByLeafType(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosLeafInfo> findByPidOrgAndPidBranchAndStatusOrderByLeafType(Long pidOrg, Long pidBranch, String status);
}
