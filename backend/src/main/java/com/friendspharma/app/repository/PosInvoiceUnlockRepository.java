package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosInvoiceUnlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PosInvoiceUnlockRepository extends JpaRepository<PosInvoiceUnlock, Long> {

    /**
     * The one authorisation that could let this edit through.
     *
     * <p>Every condition is part of the check, so a token cannot be replayed on
     * another invoice, used by anyone but the person it was issued to, spent
     * twice, or used after it expired. Looked up by hash — the plain token is
     * never stored, so it can only arrive from the caller.
     */
    @Query("""
           SELECT u FROM PosInvoiceUnlock u
            WHERE u.tokenHash   = :tokenHash
              AND u.pidTranMst  = :saleId
              AND u.requestedBy = :requesterId
              AND u.status      = 'Y'
              AND u.usedAt IS NULL
              AND u.expiresAt > :now
           """)
    Optional<PosInvoiceUnlock> findUsable(@Param("tokenHash") String tokenHash,
                                          @Param("saleId") Long saleId,
                                          @Param("requesterId") Long requesterId,
                                          @Param("now") LocalDateTime now);

    /** The audit trail, newest first. */
    @Query("""
           SELECT u FROM PosInvoiceUnlock u
            WHERE u.pidOrg = :orgId
              AND (:saleId IS NULL OR u.pidTranMst = :saleId)
            ORDER BY u.issuedAt DESC
           """)
    Page<PosInvoiceUnlock> history(@Param("orgId") Long orgId,
                                   @Param("saleId") Long saleId,
                                   Pageable pageable);
}
