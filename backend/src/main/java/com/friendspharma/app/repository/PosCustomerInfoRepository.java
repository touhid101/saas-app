package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosCustomerInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosCustomerInfoRepository extends JpaRepository<PosCustomerInfo, Long>, JpaSpecificationExecutor<PosCustomerInfo> {
    Optional<PosCustomerInfo> findByMobileNoAndStatus(String mobileNo, String status);
    /* existsByMobileNo(String) deliberately absent.
     *
     * It asked the question org-wide, so the first pharmacy to save a number
     * owned it everywhere — which is how three pharmacies ended up with three
     * different Walk-in Customer placeholders, one of them not a valid number.
     * Use the branch-scoped pair below; they match uq_customer_branch_mobile. */

    /**
     * Whether this pharmacy already holds that number.
     *
     * <p>Branch-scoped, to match {@code uq_customer_branch_mobile}. The org-wide
     * check it replaces meant the first pharmacy to save a number owned it
     * everywhere — which is why each pharmacy's Walk-in Customer ended up with a
     * different placeholder, one of them not even a valid number.
     */
    boolean existsByPidBranchAndMobileNo(Long pidBranch, String mobileNo);

    /** As above, ignoring the row being edited. */
    boolean existsByPidBranchAndMobileNoAndPidCustomerNot(Long pidBranch, String mobileNo, Long pidCustomer);

    /**
     * The one row a phone number can hold in a branch ({@code uq_customer_branch_mobile}).
     * Read by the e-commerce bridge to adopt a customer branch staff already
     * created before the same person registered online.
     */
    Optional<PosCustomerInfo> findFirstByPidBranchAndMobileNo(Long pidBranch, String mobileNo);
    List<PosCustomerInfo> findByPidOrgAndStatusOrderByCustomerName(Long pidOrg, String status);

    /** The signed-in branch's own rows. Org is kept beside branch so neither filter stands alone. */
    List<PosCustomerInfo> findByPidOrgAndPidBranchAndStatusOrderByCustomerName(Long pidOrg, Long pidBranch, String status);
    List<PosCustomerInfo> findByPidBranchAndStatusOrderByCustomerName(Long pidBranch, String status);

    @Query("SELECT c FROM PosCustomerInfo c WHERE c.pidOrg = :orgId AND c.pidBranch = :branchId " +
           "AND c.status = 'Y' " +
           "AND (LOWER(c.customerName) LIKE LOWER(CONCAT('%',:q,'%')) OR c.mobileNo LIKE CONCAT('%',:q,'%'))")
    List<PosCustomerInfo> searchByNameOrPhone(@Param("orgId") Long orgId,
                                              @Param("branchId") Long branchId,
                                              @Param("q") String q);
}
