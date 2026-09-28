package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EcomCartItemRepository extends JpaRepository<EcomCartItem, Long> {

    List<EcomCartItem> findByPidCartOrderByPidCartItem(Long pidCart);

    Optional<EcomCartItem> findByPidCartAndPidProductAndSalesUnit(Long pidCart, Long pidProduct, String salesUnit);

    Optional<EcomCartItem> findByPidCartItemAndPidCart(Long pidCartItem, Long pidCart);

    long countByPidCart(Long pidCart);

    @Modifying
    @Query("DELETE FROM EcomCartItem i WHERE i.pidCart = :cartId")
    void deleteByCart(@Param("cartId") Long cartId);
}
