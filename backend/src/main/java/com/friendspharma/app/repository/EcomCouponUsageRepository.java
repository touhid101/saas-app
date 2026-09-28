package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCouponUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface EcomCouponUsageRepository extends JpaRepository<EcomCouponUsage, Long> {

    /** Uses that still count: a released one went back when its order was cancelled. */
    @Query("SELECT COUNT(u) FROM EcomCouponUsage u WHERE u.pidCoupon = :couponId AND u.releasedAt IS NULL")
    long countLive(@Param("couponId") Long couponId);

    @Query("""
           SELECT COUNT(u) FROM EcomCouponUsage u
            WHERE u.pidCoupon = :couponId AND u.pidEcomCustomer = :customerId AND u.releasedAt IS NULL
           """)
    long countLiveForCustomer(@Param("couponId") Long couponId, @Param("customerId") Long customerId);

    Optional<EcomCouponUsage> findByPidEcomOrder(Long pidEcomOrder);

    /** What a campaign has cost so far, for the screen that decides whether to keep it. */
    @Query("SELECT COALESCE(SUM(u.amount), 0) FROM EcomCouponUsage u WHERE u.pidCoupon = :couponId AND u.releasedAt IS NULL")
    BigDecimal spentOn(@Param("couponId") Long couponId);
}
