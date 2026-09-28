package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCustomerTypeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EcomCustomerTypeHistoryRepository extends JpaRepository<EcomCustomerTypeHistory, Long> {

    List<EcomCustomerTypeHistory> findByPidEcomCustomerOrderByChangedAtDesc(Long pidEcomCustomer);
}
