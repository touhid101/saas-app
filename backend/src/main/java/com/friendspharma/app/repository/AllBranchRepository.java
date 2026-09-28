package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllBranch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllBranchRepository extends JpaRepository<AllBranch, Long>, JpaSpecificationExecutor<AllBranch> {
    List<AllBranch> findByPidOrgAndStatusOrderByBranchName(Long pidOrg, String status);

    /**
     * The one branch a user works in.
     *
     * <p>Returns a list rather than a single row because callers render it as a
     * list, and because an administrator working across a chain will pass a
     * branch they own while a Super Admin keeps using the org-wide finder above.
     */
    List<AllBranch> findByPidOrgAndPidBranchAndStatusOrderByBranchName(Long pidOrg, Long pidBranch, String status);
    List<AllBranch> findByPidRegionAndStatusOrderByBranchName(Long pidRegion, String status);

    /** [V103] The pharmacies that sell online, for the marketplace. Tenant status is checked by the caller. */
    List<AllBranch> findByEcomEnabledAndStatusOrderByBranchName(String ecomEnabled, String status);

    /** Every branch id, for the product image folders made at startup. */
    @org.springframework.data.jpa.repository.Query("select b.pidBranch from AllBranch b")
    java.util.List<Long> findAllBranchIds();
}
