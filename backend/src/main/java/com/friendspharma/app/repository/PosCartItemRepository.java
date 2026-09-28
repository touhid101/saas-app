package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosCartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosCartItemRepository extends JpaRepository<PosCartItem, Long> {
    List<PosCartItem> findByCart_PidCart(Long cartId);
    void deleteByCart_PidCart(Long cartId);

    /** [V4 Product Master] Open POS cart lines referencing this product (delete guard). */
    @Query("SELECT COUNT(i) FROM PosCartItem i WHERE i.pidProduct = :productId")
    long countByProduct(@Param("productId") Long productId);
}
