package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosSupplierInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosSupplierInfoRepository extends JpaRepository<PosSupplierInfo, Long>, JpaSpecificationExecutor<PosSupplierInfo> {
    List<PosSupplierInfo> findByPidOrgAndStatusOrderBySupplierName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosSupplierInfo> findByPidOrgAndPidBranchAndStatusOrderBySupplierName(Long pidOrg, Long pidBranch, String status);

    /* Branch-scoped, like the browse path beside it. The search used to filter on
       the organisation alone, so typing a name returned suppliers belonging to
       other pharmacies while clearing the box returned only this one's — the same
       leak that was fixed for customers and missed here. */
    @Query("SELECT s FROM PosSupplierInfo s WHERE s.pidOrg = :orgId AND s.pidBranch = :branchId " +
           "AND s.status = 'Y' " +
           "AND (LOWER(s.supplierName) LIKE LOWER(CONCAT('%',:q,'%')) OR s.mobileNo LIKE CONCAT('%',:q,'%'))")
    List<PosSupplierInfo> searchByNameOrPhone(@Param("orgId") Long orgId,
                                              @Param("branchId") Long branchId,
                                              @Param("q") String q);
}
