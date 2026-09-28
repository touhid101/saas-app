package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomDeliveryTracking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EcomDeliveryTrackingRepository extends JpaRepository<EcomDeliveryTracking, Long> {

    /** Where the order is now. The only question a customer gets to ask of this table. */
    Optional<EcomDeliveryTracking> findFirstByPidEcomOrderOrderByRecordedAtDesc(Long pidEcomOrder);

    long countByPidEcomOrder(Long pidEcomOrder);

    /** For whoever eventually decides how long positions are kept (V109 header). */
    long countByRecordedAtBefore(LocalDateTime before);
}
