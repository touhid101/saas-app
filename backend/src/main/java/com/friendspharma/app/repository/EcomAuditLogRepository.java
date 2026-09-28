package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EcomAuditLogRepository extends JpaRepository<EcomAuditLog, Long> {

    Page<EcomAuditLog> findByPidEcomCustomerOrderByActedAtDesc(Long pidEcomCustomer, Pageable pageable);
}
