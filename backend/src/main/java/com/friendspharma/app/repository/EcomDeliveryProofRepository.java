package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomDeliveryProof;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EcomDeliveryProofRepository extends JpaRepository<EcomDeliveryProof, Long> {

    Optional<EcomDeliveryProof> findByPidEcomOrder(Long pidEcomOrder);
}
