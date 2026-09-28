package com.friendspharma.app.repository;

import com.friendspharma.app.entity.TenantRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Pharmacies waiting to be approved. */
@Repository
public interface TenantRegistrationRepository extends JpaRepository<TenantRegistration, Long> {

    List<TenantRegistration> findByRegStatusOrderByInsertedDateDesc(String regStatus);

    List<TenantRegistration> findAllByOrderByInsertedDateDesc();

    Optional<TenantRegistration> findByAdminUsernameIgnoreCaseAndRegStatus(String adminUsername, String regStatus);

    long countByRegStatus(String regStatus);

}