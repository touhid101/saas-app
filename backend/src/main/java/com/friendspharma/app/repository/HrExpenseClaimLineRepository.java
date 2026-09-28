package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrExpenseClaimLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Lines are normally reached through their claim, which cascades.
 *
 * <p>This exists for the one read that does not go that way: attaching a receipt
 * to a single line, which arrives addressed to the line rather than the claim.
 */
@Repository
public interface HrExpenseClaimLineRepository extends JpaRepository<HrExpenseClaimLine, Long> {

    Optional<HrExpenseClaimLine> findByPidExpenseLineAndClaim_PidBranch(Long id, Long pidBranch);
}
