package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosRequisitionMst;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PosRequisitionMstRepository extends JpaRepository<PosRequisitionMst, Long> {

    Optional<PosRequisitionMst> findByRequisitionNo(String requisitionNo);

    /**
     * The requisition a purchase order came out of.
     *
     * <p>Read through the lines rather than the master's own PID_PURCHASE_ORDER,
     * because that column is only filled when the whole requisition went to a
     * single supplier — a split across three suppliers leaves it null and the
     * answer on the lines. Printed on the order so the buyer and the supplier
     * can quote the same shortage at each other afterwards.
     *
     * <p>A list, not an Optional: nothing stops two requisitions from feeding
     * one order, and a query that assumed otherwise would throw rather than
     * print.
     */
    /* Through the mapped association, not an ON join onto PosRequisitionDtl:
       the line entity holds PID_REQUISITION as a plain column with no
       relationship behind it, so JPQL cannot join to it that way and Spring
       refuses to start rather than issuing a broken query. */
    @Query("""
           SELECT DISTINCT m FROM PosRequisitionMst m
             JOIN m.details d
            WHERE d.pidPurchaseOrder = :purchaseId
            ORDER BY m.pidRequisition
           """)
    List<PosRequisitionMst> findByPurchaseOrder(@Param("purchaseId") Long purchaseId);

    Page<PosRequisitionMst> findByPidOrgAndStageOrderByReqDateDescPidRequisitionDesc(
            Long orgId, String stage, Pageable pageable);

    Page<PosRequisitionMst> findByPidOrgAndReqDateBetweenOrderByReqDateDescPidRequisitionDesc(
            Long orgId, LocalDate from, LocalDate to, Pageable pageable);

    Page<PosRequisitionMst> findByPidOrgAndStageAndReqDateBetweenOrderByReqDateDescPidRequisitionDesc(
            Long orgId, String stage, LocalDate from, LocalDate to, Pageable pageable);

    List<PosRequisitionMst> findByPidOrgAndStageIn(Long orgId, List<String> stages);

    /**
     * A page of requisitions, filtered on whatever was asked for.
     *
     * <p>Replaces four derived methods that were selected between by an if/else
     * ladder — adding the branch would have meant eight. Every filter is
     * optional and they combine, so one query answers all of it.
     *
     * <p>The id breaks the date tie: requisitions raised on the same day share a
     * date, and ordering on the date alone let rows reshuffle between refreshes.
     */
    @Query("""
           SELECT r FROM PosRequisitionMst r
            WHERE r.pidOrg = :orgId
              AND (:branchId IS NULL OR r.pidBranch = :branchId)
              AND (:stage    IS NULL OR r.stage     = :stage)
              AND (CAST(:from AS date) IS NULL OR r.reqDate >= :from)
              AND (CAST(:to   AS date) IS NULL OR r.reqDate <= :to)
            ORDER BY r.reqDate DESC, r.pidRequisition DESC
           """)
    Page<PosRequisitionMst> search(@Param("orgId") Long orgId,
                                   @Param("branchId") Long branchId,
                                   @Param("stage") String stage,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   Pageable pageable);

    /**
     * Every requisition for one organisation, newest first.
     *
     * <p>The id breaks the tie. Requisitions raised on the same day all carry
     * the same date, so ordering on the date alone let the rows reshuffle
     * between refreshes and a card moved out from under whoever was reading it.
     */
    Page<PosRequisitionMst> findByPidOrgOrderByReqDateDescPidRequisitionDesc(Long orgId, Pageable pageable);

    /**
     * How many requisitions sit at a stage, in one branch.
     *
     * <p>A branch is a pharmacy. The organisation-wide count this replaces told
     * a branch manager how many approvals were waiting across every pharmacy in
     * the organisation, which is neither their work nor their business.
     *
     * <p>A null branch still means every branch, for an administrator entitled
     * to that view.
     */
    @Query("""
           SELECT COUNT(r) FROM PosRequisitionMst r
            WHERE r.pidOrg = :orgId
              AND r.stage  = :stage
              AND (:branchId IS NULL OR r.pidBranch = :branchId)
           """)
    long countByBranchAndStage(@Param("orgId") Long orgId,
                               @Param("branchId") Long branchId,
                               @Param("stage") String stage);

    @Query("SELECT MAX(r.requisitionNo) FROM PosRequisitionMst r WHERE r.pidOrg = :orgId")
    String findLastRequisitionNo(@Param("orgId") Long orgId);
}
