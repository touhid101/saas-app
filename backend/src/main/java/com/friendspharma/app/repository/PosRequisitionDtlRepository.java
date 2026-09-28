package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosRequisitionDtl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosRequisitionDtlRepository extends JpaRepository<PosRequisitionDtl, Long> {
    List<PosRequisitionDtl> findByRequisition_PidRequisition(Long pidRequisition);
    void deleteByRequisition_PidRequisition(Long pidRequisition);

    /** [V4 Product Master] Requisition lines referencing this product (delete guard). */
    @Query("SELECT COUNT(d) FROM PosRequisitionDtl d WHERE d.pidProduct = :productId")
    long countByProduct(@Param("productId") Long productId);
}
