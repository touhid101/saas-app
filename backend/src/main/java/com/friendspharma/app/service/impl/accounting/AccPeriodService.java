package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.AccFiscalYear;
import com.friendspharma.app.entity.AccPeriod;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.AccFiscalYearRepository;
import com.friendspharma.app.repository.AccJournalRepository;
import com.friendspharma.app.repository.AccPeriodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Fiscal years and accounting periods.
 *
 * <p>A period exists for every date a journal is ever dated on: the first
 * posting into a month creates its fiscal year (from the configured start
 * month) and twelve monthly periods, OPEN. Closing a period stops ordinary
 * postings; locking it stops adjustments too. Nothing is ever deleted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccPeriodService {

    public static final String OPEN = "OPEN", CLOSED = "CLOSED", LOCKED = "LOCKED";

    private final AccFiscalYearRepository yearRepo;
    private final AccPeriodRepository     periodRepo;
    private final AccJournalRepository    journalRepo;
    private final AccSettings             settings;

    /**
     * Periods per organisation, so a posting does not query for one. Evicted
     * whenever a period or year changes, and — because a first posting creates
     * the periods inside its own transaction — whenever the transaction that
     * filled the cache rolls back, so uncommitted ids never survive it.
     */
    private final java.util.concurrent.ConcurrentMap<Long, List<AccPeriod>> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private List<AccPeriod> cached(Long orgId) {
        return cache.computeIfAbsent(orgId, o -> {
            UncommittedGuard.evictUnlessCommitted(() -> cache.remove(o));
            return periodRepo.findByPidOrgOrderByStartDateDesc(o);
        });
    }
    public void evict(Long orgId) { cache.remove(orgId); }

    /** The period a date falls in, created on demand. */
    @Transactional
    public AccPeriod periodFor(Long orgId, LocalDate date) {
        for (AccPeriod p : cached(orgId)) if (!date.isBefore(p.getStartDate()) && !date.isAfter(p.getEndDate())) return p;
        Optional<AccPeriod> hit = periodRepo.findCovering(orgId, date);
        if (hit.isPresent()) { cache.remove(orgId); return hit.get(); }
        AccFiscalYear year = yearRepo.findCovering(orgId, date).orElseGet(() -> createYear(orgId, date));
        cache.remove(orgId);
        return periodRepo.findCovering(orgId, date).orElseThrow(() ->
                new BusinessException("No accounting period covers " + date + " in " + year.getYearCode() + ".",
                        HttpStatus.CONFLICT, "ACC_NO_PERIOD"));
    }

    /** Refuses when a journal of the given type may not enter the period. */
    public void requireOpenFor(AccPeriod p, String journalType) {
        if (LOCKED.equals(p.getPeriodStatus())) {
            throw new BusinessException("Period " + p.getPeriodName() + " is locked. Nothing can be posted into it.",
                    HttpStatus.CONFLICT, "ACC_PERIOD_LOCKED");
        }
        if (CLOSED.equals(p.getPeriodStatus()) && !"ADJUSTMENT".equals(journalType) && !"REVERSAL".equals(journalType) && !"CLOSING".equals(journalType)) {
            throw new BusinessException("Period " + p.getPeriodName() + " is closed. Post an adjustment or reversal, or reopen the period.",
                    HttpStatus.CONFLICT, "ACC_PERIOD_CLOSED");
        }
    }

    @Transactional
    public AccFiscalYear createYear(Long orgId, LocalDate anyDate) {
        int startMonth = Math.min(12, Math.max(1, settings.integer(orgId, AccSettings.FISCAL_YEAR_START_MONTH)));
        LocalDate start = LocalDate.of(anyDate.getYear(), startMonth, 1);
        if (start.isAfter(anyDate)) start = start.minusYears(1);
        LocalDate end = start.plusYears(1).minusDays(1);
        String code = startMonth == 1 ? "FY" + start.getYear() : "FY" + start.getYear() + "-" + String.valueOf(end.getYear()).substring(2);
        LocalDate fStart = start;
        AccFiscalYear year = yearRepo.findCovering(orgId, fStart).orElseGet(() -> yearRepo.save(AccFiscalYear.builder()
                .pidOrg(orgId).yearCode(code).startDate(fStart).endDate(end).yearStatus(OPEN).build()));
        if (periodRepo.findByPidFiscalYearOrderByPeriodNoAsc(year.getPidFiscalYear()).isEmpty()) {
            for (int i = 0; i < 12; i++) {
                LocalDate ps = start.plusMonths(i);
                LocalDate pe = ps.plusMonths(1).minusDays(1);
                periodRepo.save(AccPeriod.builder().pidFiscalYear(year.getPidFiscalYear()).pidOrg(orgId).periodNo(i + 1)
                        .periodName(String.format("%d-%02d", ps.getYear(), ps.getMonthValue()))
                        .startDate(ps).endDate(pe).periodStatus(OPEN).build());
            }
            log.info("Fiscal year {} created for organisation {} with 12 periods", code, orgId);
        }
        return year;
    }

    @Transactional(readOnly = true)
    public List<AccFiscalYear> years(Long orgId) { return yearRepo.findByPidOrgOrderByStartDateDesc(orgId); }

    @Transactional(readOnly = true)
    public List<AccPeriod> periods(Long orgId, Long yearId) {
        return yearId == null ? periodRepo.findByPidOrgOrderByStartDateDesc(orgId) : periodRepo.findByPidFiscalYearOrderByPeriodNoAsc(yearId);
    }

    @Transactional
    public AccPeriod setStatus(Long orgId, Long periodId, String target, String user) {
        AccPeriod p = periodRepo.findById(periodId).filter(x -> x.getPidOrg().equals(orgId))
                .orElseThrow(() -> new BusinessException("Period not found.", HttpStatus.NOT_FOUND, "ACC_PERIOD_NOT_FOUND"));
        if (!List.of(OPEN, CLOSED, LOCKED).contains(target)) throw new BusinessException("Status must be OPEN, CLOSED or LOCKED.", HttpStatus.BAD_REQUEST, "ACC_VALIDATION");
        if (LOCKED.equals(p.getPeriodStatus()) && !LOCKED.equals(target)) {
            throw new BusinessException("A locked period stays locked.", HttpStatus.CONFLICT, "ACC_PERIOD_LOCKED");
        }
        if (CLOSED.equals(target) || LOCKED.equals(target)) {
            long drafts = journalRepo.countByPidPeriodAndJournalStatus(p.getPidPeriod(), "DRAFT");
            if (drafts > 0) throw new BusinessException(drafts + " draft journal(s) are still in " + p.getPeriodName() + ". Post or discard them first.",
                    HttpStatus.CONFLICT, "ACC_PERIOD_HAS_DRAFTS");
        }
        if (CLOSED.equals(target)) { p.setClosedBy(user); p.setClosedAt(LocalDateTime.now()); }
        if (LOCKED.equals(target)) { p.setLockedBy(user); p.setLockedAt(LocalDateTime.now()); if (p.getClosedAt() == null) { p.setClosedBy(user); p.setClosedAt(LocalDateTime.now()); } }
        p.setPeriodStatus(target);
        cache.remove(orgId);
        return periodRepo.save(p);
    }
}
