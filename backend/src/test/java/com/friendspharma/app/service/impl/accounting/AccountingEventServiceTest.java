package com.friendspharma.app.service.impl.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.DocumentNumberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The posting engine, in isolation: rules become balanced journals, an event
 * posts once, a broken rule posts nothing, and the day-sheet guard holds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountingEventServiceTest {

    static final Long ORG = 7L, BRANCH = 3L;
    static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    @Mock AccEventRepository eventRepo;
    @Mock AccRuleRepository ruleRepo;
    @Mock AccRuleLineRepository ruleLineRepo;
    @Mock AccJournalRepository journalRepo;
    @Mock AccJournalLineRepository lineRepo;
    @Mock AccAccountRepository accountRepo;
    @Mock AccBusinessDayRepository dayRepo;
    @Mock AccChartService chart;
    @Mock AccPeriodService periods;
    @Mock AccSettings settings;
    @Mock DocumentNumberService numbers;
    @Mock AccEventFailureRecorder failures;
    @Mock AccLedgerVersion ledgerVersion;
    @Spy ObjectMapper json = new ObjectMapper();

    @InjectMocks AccountingEventService engine;

    final AccAccount till = acct(101L, "1101", "Cash in till", "CASH_REGISTER");
    final AccAccount bank = acct(102L, "1102", "Bank", "BANK_DEFAULT");
    final AccAccount ar = acct(110L, "1201", "Receivables", "AR");
    final AccAccount revenue = acct(401L, "4101", "Sales", "SALES_REVENUE");
    final AccAccount assets = AccAccount.builder().pidAccount(100L).accountCode("1000").accountName("Assets").isGroup(1).roleKey("ASSETS").build();

    @BeforeEach
    void wire() {
        when(settings.is(ORG, AccSettings.POSTING_ENABLED)).thenReturn(true);
        when(settings.is(ORG, AccSettings.REQUIRE_DAY_OPEN)).thenReturn(false);
        when(settings.get(ORG, AccSettings.LATE_POSTING_POLICY)).thenReturn("REFUSE");
        when(settings.get(ORG, AccSettings.CURRENCY)).thenReturn("BDT");
        when(numbers.nextNumber(eq("SEQ_JOURNAL_NO"), eq("JV"))).thenReturn("JV260900001");
        when(periods.periodFor(eq(ORG), any())).thenReturn(AccPeriod.builder().pidPeriod(9L).periodStatus("OPEN").build());
        for (AccAccount a : List.of(till, bank, ar, revenue)) {
            when(chart.byRole(ORG, a.getRoleKey())).thenReturn(a);
            when(chart.roleOf(ORG, a.getPidAccount())).thenReturn(a.getRoleKey());
            when(chart.cachedById(ORG, a.getPidAccount())).thenReturn(Optional.of(a));
        }
        when(chart.byRole(ORG, "ASSETS")).thenReturn(assets);
        when(chart.byPaymentMode(ORG, "CASH")).thenReturn(Optional.of(till));
        when(chart.byPaymentMode(ORG, "BANK")).thenReturn(Optional.of(bank));
        when(chart.byPaymentMode(eq(ORG), argThat(m -> m == null || m.equals("CREDIT")))).thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(500);
        when(journalRepo.save(any())).thenAnswer(inv -> { AccJournal j = inv.getArgument(0); if (j.getPidJournal() == null) j.setPidJournal(ids.incrementAndGet()); return j; });
        when(eventRepo.save(any())).thenAnswer(inv -> { AccEvent e = inv.getArgument(0); if (e.getPidEvent() == null) e.setPidEvent(ids.incrementAndGet()); return e; });
        when(lineRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.findByPidOrgAndSourceModuleAndSourceTypeAndSourceIdAndEventKey(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(dayRepo.findByPidBranchAndBusinessDate(any(), any())).thenReturn(Optional.empty());
    }

    /** SALE_CREATED as the default rules define it: Dr @MODE cash, Dr AR credit part, Cr revenue. */
    void rule(String eventType, AccRuleLine... lines) {
        when(ruleRepo.findEffective(ORG, eventType)).thenReturn(List.of(AccRule.builder().pidRule(1L).pidOrg(null).eventType(eventType).isActive(1).build()));
        when(ruleLineRepo.findByPidRuleOrderByLineNoAsc(1L)).thenReturn(List.of(lines));
    }

    static AccRuleLine rl(int n, String side, String role, String amountKey, String party) {
        return AccRuleLine.builder().pidRuleLine((long) n).pidRule(1L).lineNo(n).side(side).accountRole(role).amountKey(amountKey).partyType(party).build();
    }

    static AccAccount acct(Long id, String code, String name, String role) {
        return AccAccount.builder().pidAccount(id).pidOrg(ORG).accountCode(code).accountName(name).roleKey(role).isGroup(0).isActive(1).build();
    }

    FinancialEvent sale(BigDecimal paid, BigDecimal credit, String mode) {
        return FinancialEvent.of("SALES", "SALE", 42L, "SALE_CREATED", "SALE_CREATED").in(ORG, BRANCH).on(DAY)
                .ref("SL-42", "Sale SL-42").mode(mode).party("CUSTOMER", 9L, "ABC")
                .amount("PAID", paid).amount("CREDIT", credit).amount("NET", paid.add(credit));
    }

    @Nested class Posting {
        @Test void aRuleBecomesABalancedPostedJournal() {
            engine.evictRules();
            rule("SALE_CREATED", rl(1, "D", "@MODE", "PAID", null), rl(2, "D", "AR", "CREDIT", "CUSTOMER"), rl(3, "C", "SALES_REVENUE", "NET", null));
            AccJournal j = engine.post(sale(new BigDecimal("60"), new BigDecimal("40"), "CASH"));

            assertThat(j).isNotNull();
            assertThat(j.getJournalStatus()).isEqualTo("POSTED");
            assertThat(j.getJournalNo()).isEqualTo("JV260900001");
            assertThat(j.getTotalDebit()).isEqualByComparingTo("100.00");
            assertThat(j.getTotalCredit()).isEqualByComparingTo("100.00");
            assertThat(j.getPidPeriod()).isEqualTo(9L);

            ArgumentCaptor<AccJournalLine> cap = ArgumentCaptor.forClass(AccJournalLine.class);
            verify(lineRepo, times(3)).save(cap.capture());
            List<AccJournalLine> lines = cap.getAllValues();
            assertThat(lines.get(0).getPidAccount()).isEqualTo(till.getPidAccount());
            assertThat(lines.get(0).getDebit()).isEqualByComparingTo("60.00");
            assertThat(lines.get(1).getPidAccount()).isEqualTo(ar.getPidAccount());
            assertThat(lines.get(1).getPartyType()).isEqualTo("CUSTOMER");   // only the AR line carries the party
            assertThat(lines.get(1).getPartyId()).isEqualTo(9L);
            assertThat(lines.get(0).getPartyId()).isNull();
            assertThat(lines.get(2).getCredit()).isEqualByComparingTo("100.00");
            assertThat(lines).allSatisfy(l -> assertThat(l.getPidBranch()).isEqualTo(BRANCH));

            ArgumentCaptor<AccEvent> ev = ArgumentCaptor.forClass(AccEvent.class);
            verify(eventRepo).save(ev.capture());
            assertThat(ev.getValue().getEventStatus()).isEqualTo("POSTED");
            assertThat(ev.getValue().getPidJournal()).isEqualTo(j.getPidJournal());
            verifyNoInteractions(failures);
        }

        @Test void aZeroAmountLineIsLeftOutAndAllZeroIsSkipped() {
            engine.evictRules();
            rule("SALE_CREATED", rl(1, "D", "@MODE", "PAID", null), rl(2, "D", "AR", "CREDIT", "CUSTOMER"), rl(3, "C", "SALES_REVENUE", "NET", null));
            AccJournal j = engine.post(sale(new BigDecimal("100"), BigDecimal.ZERO, "CASH"));
            verify(lineRepo, times(2)).save(any());
            assertThat(j.getTotalDebit()).isEqualByComparingTo("100.00");

            reset(lineRepo, eventRepo);
            when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            AccJournal none = engine.post(FinancialEvent.of("SALES", "SALE", 43L, "SALE_CREATED", "SALE_CREATED").in(ORG, BRANCH).on(DAY).mode("CASH")
                    .amount("PAID", BigDecimal.ZERO).amount("CREDIT", BigDecimal.ZERO).amount("NET", BigDecimal.ZERO));
            assertThat(none).isNull();
            ArgumentCaptor<AccEvent> ev = ArgumentCaptor.forClass(AccEvent.class);
            verify(eventRepo).save(ev.capture());
            assertThat(ev.getValue().getEventStatus()).isEqualTo("SKIPPED");
            verify(lineRepo, never()).save(any());
        }

        @Test void aNegativeAmountSwapsTheSideInsteadOfWritingANegativeLine() {
            engine.evictRules();
            rule("CASH_VARIANCE", rl(1, "D", "CASH_REGISTER", "AMOUNT", null), rl(2, "C", "SALES_REVENUE", "AMOUNT", null));
            engine.post(FinancialEvent.of("CASH", "DAY", 1L, "VAR", "CASH_VARIANCE").in(ORG, BRANCH).on(DAY).amount("AMOUNT", new BigDecimal("-25")));
            ArgumentCaptor<AccJournalLine> cap = ArgumentCaptor.forClass(AccJournalLine.class);
            verify(lineRepo, times(2)).save(cap.capture());
            assertThat(cap.getAllValues().get(0).getCredit()).isEqualByComparingTo("25.00");   // till credited (a shortage)
            assertThat(cap.getAllValues().get(0).getDebit()).isEqualByComparingTo("0");
            assertThat(cap.getAllValues().get(1).getDebit()).isEqualByComparingTo("25.00");
        }

        @Test void aCreditSaleWithNoCashModeStillFindsTheTillForRealMoney() {
            engine.evictRules();
            rule("SALE_CREATED", rl(1, "D", "@MODE", "PAID", null), rl(2, "D", "AR", "CREDIT", "CUSTOMER"), rl(3, "C", "SALES_REVENUE", "NET", null));
            engine.post(sale(new BigDecimal("10"), new BigDecimal("90"), "CREDIT"));
            ArgumentCaptor<AccJournalLine> cap = ArgumentCaptor.forClass(AccJournalLine.class);
            verify(lineRepo, times(3)).save(cap.capture());
            assertThat(cap.getAllValues().get(0).getPidAccount()).isEqualTo(till.getPidAccount());
        }

        @Test void splitsMakeOneLinePerParty() {
            engine.evictRules();
            rule("PAYROLL_POSTED", rl(1, "D", "SALES_REVENUE", "GROSS", null), rl(2, "C", "AR", "NET", "EMPLOYEE"));
            FinancialEvent ev = FinancialEvent.of("HR", "PAYROLL", 5L, "POST", "PAYROLL_POSTED").in(ORG, BRANCH).on(DAY).amount("GROSS", new BigDecimal("300"));
            ev.split("NET", "EMPLOYEE", 1L, "A", new BigDecimal("100")).split("NET", "EMPLOYEE", 2L, "B", new BigDecimal("200"));
            AccJournal j = engine.post(ev);
            assertThat(j.getTotalCredit()).isEqualByComparingTo("300.00");
            ArgumentCaptor<AccJournalLine> cap = ArgumentCaptor.forClass(AccJournalLine.class);
            verify(lineRepo, times(3)).save(cap.capture());
            assertThat(cap.getAllValues().stream().filter(l -> "EMPLOYEE".equals(l.getPartyType())).map(AccJournalLine::getPartyId)).containsExactly(1L, 2L);
        }
    }

    @Nested class Refusals {
        @Test void anUnbalancedRulePostsNothingAndRecordsTheFailure() {
            engine.evictRules();
            rule("SALE_CREATED", rl(1, "D", "@MODE", "PAID", null), rl(2, "C", "SALES_REVENUE", "NET", null));   // AR line missing
            assertThatThrownBy(() -> engine.post(sale(new BigDecimal("60"), new BigDecimal("40"), "CASH")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("does not balance");
            verify(journalRepo, never()).save(any());
            verify(lineRepo, never()).save(any());
            verify(failures).record(any(), contains("does not balance"));
        }

        @Test void aMissingRuleIsAConfigurationError() {
            engine.evictRules();
            when(ruleRepo.findEffective(ORG, "NOPE")).thenReturn(List.of());
            assertThatThrownBy(() -> engine.post(FinancialEvent.of("X", "Y", 1L, "K", "NOPE").in(ORG, BRANCH).on(DAY).amount("A", BigDecimal.ONE)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("No accounting rule");
        }

        @Test void aGroupAccountCannotTakeAPosting() {
            engine.evictRules();
            rule("ODD", rl(1, "D", "ASSETS", "A", null), rl(2, "C", "SALES_REVENUE", "A", null));
            assertThatThrownBy(() -> engine.post(FinancialEvent.of("X", "Y", 1L, "K", "ODD").in(ORG, BRANCH).on(DAY).amount("A", BigDecimal.TEN)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("group account");
        }

        @Test void anEventWithoutItsIdentityIsRefusedBeforeAnythingIsRead() {
            assertThatThrownBy(() -> engine.post(FinancialEvent.of("X", null, 1L, "K", "T").in(ORG, BRANCH)))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(ruleRepo);
        }
    }

    @Nested class Idempotency {
        @Test void anEventAlreadyPostedReturnsItsJournalWithoutPostingAgain() {
            AccEvent done = AccEvent.builder().pidEvent(1L).pidOrg(ORG).eventStatus("POSTED").pidJournal(77L).build();
            when(eventRepo.findByPidOrgAndSourceModuleAndSourceTypeAndSourceIdAndEventKey(ORG, "SALES", "SALE", 42L, "SALE_CREATED")).thenReturn(Optional.of(done));
            AccJournal existing = AccJournal.builder().pidJournal(77L).journalStatus("POSTED").build();
            when(journalRepo.findById(77L)).thenReturn(Optional.of(existing));

            AccJournal j = engine.post(sale(new BigDecimal("60"), new BigDecimal("40"), "CASH"));
            assertThat(j).isSameAs(existing);
            verify(journalRepo, never()).save(any());
            verify(eventRepo, never()).save(any());
            verifyNoInteractions(ruleRepo);
        }

        @Test void aReversedEventNeedsANewRevisionKey() {
            AccEvent rev = AccEvent.builder().pidEvent(1L).pidOrg(ORG).eventStatus("REVERSED").build();
            when(eventRepo.findByPidOrgAndSourceModuleAndSourceTypeAndSourceIdAndEventKey(ORG, "SALES", "SALE", 42L, "SALE_CREATED")).thenReturn(Optional.of(rev));
            assertThatThrownBy(() -> engine.post(sale(BigDecimal.TEN, BigDecimal.ZERO, "CASH"))).isInstanceOf(BusinessException.class).hasMessageContaining("reversed");
        }

        @Test void revisionKeysCountEarlierPostingsOfTheSameDocument() {
            when(eventRepo.findBySourceModuleAndSourceTypeAndSourceIdOrderByPidEventAsc("SALES", "SALE", 42L)).thenReturn(List.of(
                    AccEvent.builder().pidOrg(ORG).eventKey("SALE_CREATED").build(),
                    AccEvent.builder().pidOrg(ORG).eventKey("SALE_CREATED:REV").build(),
                    AccEvent.builder().pidOrg(ORG).eventKey("SALE_CREATED:R2").build(),
                    AccEvent.builder().pidOrg(99L).eventKey("SALE_CREATED").build()));   // another organisation's books
            assertThat(engine.nextRevisionKey(ORG, "SALES", "SALE", 42L, "SALE_CREATED")).isEqualTo("SALE_CREATED:R3");
            when(eventRepo.findBySourceModuleAndSourceTypeAndSourceIdOrderByPidEventAsc("SALES", "SALE", 1L)).thenReturn(List.of());
            assertThat(engine.nextRevisionKey(ORG, "SALES", "SALE", 1L, "SALE_CREATED")).isEqualTo("SALE_CREATED");
        }

        @Test void postingSwitchedOffRecordsTheEventAsSkipped() {
            when(settings.is(ORG, AccSettings.POSTING_ENABLED)).thenReturn(false);
            assertThat(engine.post(sale(BigDecimal.TEN, BigDecimal.ZERO, "CASH"))).isNull();
            ArgumentCaptor<AccEvent> ev = ArgumentCaptor.forClass(AccEvent.class);
            verify(eventRepo).save(ev.capture());
            assertThat(ev.getValue().getEventStatus()).isEqualTo("SKIPPED");
            verifyNoInteractions(ruleRepo, journalRepo);
        }
    }

    @Nested class ClosedDay {
        @BeforeEach void ruleTouchingTheTill() {
            engine.evictRules();
            rule("SALE_CREATED", rl(1, "D", "@MODE", "PAID", null), rl(2, "D", "AR", "CREDIT", "CUSTOMER"), rl(3, "C", "SALES_REVENUE", "NET", null));
        }

        @Test void aCashPostingIntoAClosedDayIsRefused() {
            when(dayRepo.findByPidBranchAndBusinessDate(BRANCH, DAY)).thenReturn(Optional.of(AccBusinessDay.builder().dayStatus("CLOSED").build()));
            assertThatThrownBy(() -> engine.post(sale(new BigDecimal("100"), BigDecimal.ZERO, "CASH")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("already closed");
            verify(journalRepo, never()).save(any());
        }

        @Test void aBankPostingOnAClosedDayIsFine() {
            when(dayRepo.findByPidBranchAndBusinessDate(BRANCH, DAY)).thenReturn(Optional.of(AccBusinessDay.builder().dayStatus("CLOSED").build()));
            AccJournal j = engine.post(sale(new BigDecimal("100"), BigDecimal.ZERO, "BANK"));
            assertThat(j.getJournalDate()).isEqualTo(DAY);
        }

        @Test void nextDayPolicyDatesThePostingOnTheFirstDayNotClosed() {
            when(settings.get(ORG, AccSettings.LATE_POSTING_POLICY)).thenReturn("NEXT_DAY");
            when(dayRepo.findByPidBranchAndBusinessDate(BRANCH, DAY)).thenReturn(Optional.of(AccBusinessDay.builder().dayStatus("CLOSED").build()));
            when(dayRepo.findByPidBranchAndBusinessDate(BRANCH, DAY.plusDays(1))).thenReturn(Optional.of(AccBusinessDay.builder().dayStatus("LOCKED").build()));
            AccJournal j = engine.post(sale(new BigDecimal("100"), BigDecimal.ZERO, "CASH"));
            assertThat(j.getJournalDate()).isEqualTo(DAY.plusDays(2));
        }

        @Test void historicalBackfillIgnoresTheDayGuard() {
            when(dayRepo.findByPidBranchAndBusinessDate(BRANCH, DAY)).thenReturn(Optional.of(AccBusinessDay.builder().dayStatus("CLOSED").build()));
            AccJournal j = engine.post(sale(new BigDecimal("100"), BigDecimal.ZERO, "CASH").historical(12L));
            assertThat(j.getJournalDate()).isEqualTo(DAY);
            assertThat(j.getJournalType()).isEqualTo("AUTO");
        }

        @Test void requireDayOpenRefusesADayNobodyOpened() {
            when(settings.is(ORG, AccSettings.REQUIRE_DAY_OPEN)).thenReturn(true);
            assertThatThrownBy(() -> engine.post(sale(new BigDecimal("100"), BigDecimal.ZERO, "CASH")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("has not been opened");
        }
    }
}
