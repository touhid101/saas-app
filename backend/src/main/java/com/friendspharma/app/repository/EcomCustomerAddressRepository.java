package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EcomCustomerAddressRepository extends JpaRepository<EcomCustomerAddress, Long> {

    List<EcomCustomerAddress> findByPidEcomCustomerAndStatusOrderByIsDefaultDescPidAddressAsc(Long pidEcomCustomer, String status);

    /** Ownership is part of the key: an address is only ever read together with its customer. */
    Optional<EcomCustomerAddress> findByPidAddressAndPidEcomCustomerAndStatus(Long pidAddress, Long pidEcomCustomer, String status);

    long countByPidEcomCustomerAndStatus(Long pidEcomCustomer, String status);

    @Modifying
    @Query("UPDATE EcomCustomerAddress a SET a.isDefault = 'N' WHERE a.pidEcomCustomer = :customerId AND a.isDefault = 'Y'")
    void clearDefault(@Param("customerId") Long customerId);
}
