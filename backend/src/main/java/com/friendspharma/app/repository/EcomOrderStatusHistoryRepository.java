package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomOrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EcomOrderStatusHistoryRepository extends JpaRepository<EcomOrderStatusHistory, Long> {
    List<EcomOrderStatusHistory> findByPidEcomOrderOrderByChangedAt(Long pidEcomOrder);
}
