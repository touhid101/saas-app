package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccProfitAllocation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccProfitAllocationRepository extends JpaRepository<AccProfitAllocation, Long> {
    List<AccProfitAllocation> findByPidDistributionOrderByPidAllocationAsc(Long pidDistribution);
    List<AccProfitAllocation> findByPidInvestorOrderByPidAllocationDesc(Long pidInvestor);
    void deleteByPidDistribution(Long pidDistribution);
}
