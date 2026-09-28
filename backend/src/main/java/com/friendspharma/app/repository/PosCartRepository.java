package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosCart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PosCartRepository extends JpaRepository<PosCart, Long> {

    /** Active cart for the current user (OPEN) */
    Optional<PosCart> findByPidUserAndStage(Long userId, String stage);

    /** All held carts for a branch */
    List<PosCart> findByPidBranchAndStageOrderByUpdatedDateDesc(Long branchId, String stage);

    /** All OPEN / HELD carts for a user */
    List<PosCart> findByPidUserAndStageIn(Long userId, List<String> stages);

    Optional<PosCart> findByCartRef(String cartRef);

    /** Find OPEN/HELD carts older than threshold — used by the cleanup scheduler */
    @Query("SELECT c FROM PosCart c WHERE c.stage IN ('OPEN','HELD') AND c.updatedDate < :threshold")
    List<PosCart> findStaleCarts(@Param("threshold") LocalDateTime threshold);
}
