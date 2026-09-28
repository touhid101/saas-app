package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosBoxInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosBoxInfoRepository extends JpaRepository<PosBoxInfo, Long> {
    List<PosBoxInfo> findByPidOrgAndStatusOrderByBoxSizeTitle(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosBoxInfo> findByPidOrgAndPidBranchAndStatusOrderByBoxSizeTitle(Long pidOrg, Long pidBranch, String status);
    List<PosBoxInfo> findByPidLeafAndStatusOrderByBoxSizeTitle(Long pidLeaf, String status);
}
