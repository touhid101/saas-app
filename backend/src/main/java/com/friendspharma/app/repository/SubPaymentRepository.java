package com.friendspharma.app.repository;

import com.friendspharma.app.entity.SubPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Money received against subscription invoices. */
@Repository
public interface SubPaymentRepository extends JpaRepository<SubPayment, Long> {

    List<SubPayment> findByPidBranchOrderByPidPaymentDesc(Long pidBranch);

    List<SubPayment> findByPidInvoiceOrderByPidPaymentDesc(Long pidInvoice);

    /** The guard that makes a repeated gateway callback a no-op. */
    Optional<SubPayment> findByIdempotencyKey(String idempotencyKey);

}