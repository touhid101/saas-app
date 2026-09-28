package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCouponBranch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EcomCouponBranchRepository extends JpaRepository<EcomCouponBranch, Long> {

    List<EcomCouponBranch> findByPidCoupon(Long pidCoupon);

    boolean existsByPidCouponAndPidBranch(Long pidCoupon, Long pidBranch);

    void deleteByPidCoupon(Long pidCoupon);
}
