package com.friendspharma.app.repository;

import com.friendspharma.app.entity.SubSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A branch's subscription.
 *
 * <p>"Live" means ACTIVE or SUSPENDED — a suspended branch still holds its
 * subscription, it simply cannot use it. CANCELLED and EXPIRED rows are history.
 */
@Repository
public interface SubSubscriptionRepository extends JpaRepository<SubSubscription, Long> {

    @Query("SELECT s FROM SubSubscription s WHERE s.pidBranch = :branchId "
         + "AND s.subStatus IN ('ACTIVE','SUSPENDED')")
    Optional<SubSubscription> findLiveForBranch(@Param("branchId") Long branchId);

    List<SubSubscription> findByPidBranchOrderByPidSubscriptionDesc(Long pidBranch);

    /** Live subscriptions whose grace has run out, for the nightly sweep. */
    @Query("SELECT s FROM SubSubscription s WHERE s.subStatus = 'ACTIVE' "
         + "AND :today > s.expiresOn")
    List<SubSubscription> findPastExpiry(@Param("today") LocalDate today);

    @Query("SELECT s FROM SubSubscription s WHERE s.subStatus IN ('ACTIVE','SUSPENDED') "
         + "ORDER BY s.expiresOn")
    List<SubSubscription> findAllLive();

}