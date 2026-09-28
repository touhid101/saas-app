package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.AccEvent;
import com.friendspharma.app.repository.AccEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Writes a FAILED accounting event in its own transaction.
 *
 * <p>The engine throws so that the business transaction rolls back — the
 * right outcome — but a row that rolls back with it tells nobody what went
 * wrong. Same pattern as {@code PasswordResetAuditRecorder}: REQUIRES_NEW,
 * and a failure here is logged, never rethrown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccEventFailureRecorder {

    private final AccEventRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(FinancialEvent ev, String error) {
        try {
            AccEvent e = repo.findByPidOrgAndSourceModuleAndSourceTypeAndSourceIdAndEventKey(
                            ev.orgId, ev.sourceModule, ev.sourceType, ev.sourceId, ev.eventKey)
                    .orElseGet(() -> AccEvent.builder().pidOrg(ev.orgId).pidBranch(ev.branchId)
                            .sourceModule(ev.sourceModule).sourceType(ev.sourceType).sourceId(ev.sourceId)
                            .eventKey(ev.eventKey).retryCount(0).build());
            if (AccountingEventService.POSTED.equals(e.getEventStatus())) return;
            e.setEventType(ev.eventType);
            e.setEventDate(ev.date);
            e.setReference(AccountingEventService.trim(ev.reference, 60));
            e.setDescription(AccountingEventService.trim(ev.description, 300));
            e.setAmount(ev.headline().setScale(2, RoundingMode.HALF_UP));
            e.setEventStatus(AccountingEventService.FAILED);
            e.setErrorMessage(AccountingEventService.trim(error, 1000));
            e.setRetryCount((e.getRetryCount() == null ? 0 : e.getRetryCount()) + 1);
            e.setProcessedAt(LocalDateTime.now());
            e.setPidBackfillBatch(ev.backfillBatchId);
            repo.save(e);
        } catch (RuntimeException ex) {
            log.error("Could not record the failed accounting event {}: {}", ev.identity(), ex.getMessage());
        }
    }
}
