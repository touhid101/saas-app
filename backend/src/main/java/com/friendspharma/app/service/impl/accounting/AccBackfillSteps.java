package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.AccBackfillBatch;
import com.friendspharma.app.entity.PosSupplierPayment;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.AccBackfillBatchRepository;
import com.friendspharma.app.repository.PosSupplierPaymentRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The steps of a backfill that need their own transaction: the batch row
 * (kept whatever happens to the documents) and each document (a failure
 * records a FAILED event and leaves the rest of the batch untouched).
 * A separate bean so the proxy is real — a method calling itself never
 * crosses a transaction boundary.
 */
@Component
@RequiredArgsConstructor
public class AccBackfillSteps {

    private final AccBackfillBatchRepository  batchRepo;
    private final AccountingHooks             hooks;
    private final PosTransactionMstRepository mstRepo;
    private final PosSupplierPaymentRepository payRepo;
    private final AccScope                    scope;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccBackfillBatch startBatch(Long org, Long branch, String module, LocalDate from, LocalDate to, boolean dryRun) {
        return batchRepo.save(AccBackfillBatch.builder().pidOrg(org).pidBranch(branch).scopeModule(module == null || module.isBlank() ? null : module).dateFrom(from).dateTo(to)
                .dryRun(dryRun ? 1 : 0).batchStatus("RUNNING").startedBy(scope.username()).startedAt(LocalDateTime.now()).build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccBackfillBatch finishBatch(Long id, int scanned, int posted, int skipped, int failed, String summary, String status) {
        AccBackfillBatch b = batchRepo.findById(id).orElseThrow();
        b.setScannedCount(scanned); b.setPostedCount(posted); b.setSkippedCount(skipped); b.setFailedCount(failed);
        b.setSummary(summary); b.setBatchStatus(status); b.setFinishedAt(LocalDateTime.now());
        return batchRepo.save(b);
    }

    /** One document, its own transaction: a failure records a FAILED event and leaves the rest of the batch untouched. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postOne(String module, String type, Long id, Long batch) {
        switch (module + "/" + type) {
            case "SALES/SALE" -> mstRepo.findById(id).ifPresent(m -> hooks.backfillSale(m, batch));
            case "SALES/SALES_RETURN" -> mstRepo.findById(id).ifPresent(m -> hooks.backfillSalesReturn(m, batch));
            case "PURCHASE/PURCHASE" -> mstRepo.findById(id).ifPresent(m -> hooks.backfillPurchase(m, batch));
            case "PURCHASE/PURCHASE_RETURN" -> mstRepo.findById(id).ifPresent(m -> hooks.backfillPurchaseReturn(m, batch));
            case "SUPPLIER_PAYMENT/SUPPLIER_PAYMENT" -> payRepo.findById(id).ifPresent(p -> hooks.backfillSupplierPayment(p, batch));
            default -> throw new BusinessException("Events from " + module + " are re-posted from their own screen.", HttpStatus.CONFLICT, "ACC_RETRY_UNSUPPORTED");
        }
    }

}
