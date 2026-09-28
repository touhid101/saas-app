package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCoupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EcomCouponRepository extends JpaRepository<EcomCoupon, Long> {

    /** Codes are typed by people: SAVE100 and save100 are the same coupon. */
    @Query("SELECT c FROM EcomCoupon c WHERE LOWER(c.code) = LOWER(:code)")
    Optional<EcomCoupon> findByCode(@Param("code") String code);

    /**
     * The same lookup, row-locked, for the moment of redemption.
     *
     * <p>The usage row's unique index is what actually stops a coupon being
     * spent past its limit; this serialises the readers so the second one is
     * refused with a sentence rather than a constraint violation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM EcomCoupon c WHERE LOWER(c.code) = LOWER(:code)")
    Optional<EcomCoupon> lockByCode(@Param("code") String code);

    @Query("""
           SELECT c FROM EcomCoupon c
            WHERE (:active = '' OR c.isActive = :active)
              AND (:q = '' OR LOWER(c.code) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(c.couponName) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY c.isActive DESC, c.pidCoupon DESC
           """)
    Page<EcomCoupon> search(@Param("active") String active, @Param("q") String q, Pageable pageable);
}
