package com.friendspharma.app.repository;

import com.friendspharma.app.entity.SubInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Subscription invoices. */
@Repository
public interface SubInvoiceRepository extends JpaRepository<SubInvoice, Long> {

    List<SubInvoice> findByPidBranchOrderByPidInvoiceDesc(Long pidBranch);

    List<SubInvoice> findByPidBranchAndPayStatusOrderByDueOn(Long pidBranch, String payStatus);

    Optional<SubInvoice> findByInvoiceNoIgnoreCase(String invoiceNo);

    @Query("SELECT COALESCE(MAX(i.pidInvoice), 0) FROM SubInvoice i")
    Long maxId();

}