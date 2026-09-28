package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccCashVoucher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccCashVoucherRepository extends JpaRepository<AccCashVoucher, Long> {
    Optional<AccCashVoucher> findByPidCashVoucherAndPidOrg(Long id, Long pidOrg);
    @Query("select v from AccCashVoucher v where v.pidOrg = :org and (:branch is null or v.pidBranch = :branch) and (:status is null or v.voucherStatus = :status) and (:kind is null or v.voucherKind = :kind) and v.voucherDate between :from and :to order by v.voucherDate desc, v.pidCashVoucher desc")
    Page<AccCashVoucher> search(@Param("org") Long pidOrg, @Param("branch") Long branch, @Param("status") String status, @Param("kind") String kind, @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);
}
