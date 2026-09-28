package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosProductUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosProductUnitRepository extends JpaRepository<PosProductUnit, Long> {
    List<PosProductUnit> findByPidOrgAndStatusOrderByUnitName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosProductUnit> findByPidOrgAndPidBranchAndStatusOrderByUnitName(Long pidOrg, Long pidBranch, String status);
}
