package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCustomerAuth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EcomCustomerAuthRepository extends JpaRepository<EcomCustomerAuth, Long> {

    Optional<EcomCustomerAuth> findByLoginIdentifier(String loginIdentifier);

    Optional<EcomCustomerAuth> findByPidEcomCustomer(Long pidEcomCustomer);

    boolean existsByLoginIdentifier(String loginIdentifier);
}
