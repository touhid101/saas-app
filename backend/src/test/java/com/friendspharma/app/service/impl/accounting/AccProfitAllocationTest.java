package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The share maths of a profit distribution: each policy method, with the
 * capital figures read from the investors' ledger statements.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccProfitAllocationTest {

    static final Long ORG = 1L;
    static final LocalDate FROM = LocalDate.of(2026, 9, 1), TO = LocalDate.of(2026, 9, 30);   // 30 days

    @Mock AccScope scope;
    @Mock AccProfitPolicyRepository policyRepo;
    @Mock AccProfitPolicyLineRepository policyLineRepo;
    @Mock AccProfitDistributionRepository distRepo;
    @Mock AccProfitAllocationRepository allocationRepo;
    @Mock AccInvestorRepository investorRepo;
    @Mock AccInvestorTxnRepository txnRepo;
    @Mock AccPeriodRepository periodRepo;
    @Mock AccLedgerService ledger;
    @Mock AccInvestorService investors;
    @Mock AccountingEventService engine;
    @Mock com.friendspharma.app.service.impl.DocumentNumberService numbers;
    @Mock com.fasterxml.jackson.databind.ObjectMapper json;

    @InjectMocks AccProfitDistributionService service;

    final AccInvestor a = AccInvestor.builder().pidInvestor(1L).pidOrg(ORG).investorCode("A").investorName("Investor A").investorStatus("ACTIVE").sharePct(new BigDecimal("60")).build();
    final AccInvestor b = AccInvestor.builder().pidInvestor(2L).pidOrg(ORG).investorCode("B").investorName("Investor B").investorStatus("ACTIVE").sharePct(new BigDecimal("40")).build();
    final AccInvestor gone = AccInvestor.builder().pidInvestor(3L).pidOrg(ORG).investorCode("C").investorName("Exited").investorStatus("EXITED").build();
    final AccProfitDistribution dist = AccProfitDistribution.builder().pidDistribution(10L).pidOrg(ORG).periodFrom(FROM).periodTo(TO).build();

    @BeforeEach
    void wire() {
        when(investorRepo.findByPidOrgOrderByInvestorCodeAsc(ORG)).thenReturn(List.of(a, b, gone));
        when(allocationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(policyLineRepo.findByPidProfitPolicy(any())).thenReturn(List.of());
    }

    /** A capital statement: opening at period start, then (date, credit=addition, debit=withdrawal) rows. */
    void statement(Long investorId, String opening, Object[][] rows) {
        BigDecimal open = new BigDecimal(opening), run = open;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal cr = new BigDecimal((String) r[1]), dr = new BigDecimal((String) r[2]);
            run = run.add(cr).subtract(dr);
            Map<String, Object> m = new LinkedHashMap<>(); m.put("date", r[0]); m.put("credit", cr); m.put("debit", dr); list.add(m);
        }
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("openingBalance", open); st.put("closingBalance", run); st.put("rows", list);
        when(ledger.partyStatement(ORG, null, "INVESTOR", investorId, "INVESTOR_CAPITAL", FROM, TO)).thenReturn(st);
    }

    AccProfitPolicy policy(String method) { return AccProfitPolicy.builder().pidProfitPolicy(5L).pidOrg(ORG).shareMethod(method).build(); }

    List<AccProfitAllocation> saved() {
        ArgumentCaptor<AccProfitAllocation> cap = ArgumentCaptor.forClass(AccProfitAllocation.class);
        verify(allocationRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    @Test void capitalRatioSharesByClosingCapitalAndTheLastInvestorTakesTheRoundingRemainder() {
        statement(1L, "1000", new Object[][]{});
        statement(2L, "2000", new Object[][]{});
        service.allocate(dist, policy("CAPITAL_RATIO"), new BigDecimal("100.00"));
        List<AccProfitAllocation> rows = saved();
        assertThat(rows).hasSize(2);                       // the exited investor is not a party
        assertThat(rows.get(0).getSharePct()).isEqualByComparingTo("33.3333");
        assertThat(rows.get(0).getAllocatedAmount()).isEqualByComparingTo("33.33");
        assertThat(rows.get(1).getSharePct()).isEqualByComparingTo("66.6667");
        assertThat(rows.get(1).getAllocatedAmount()).isEqualByComparingTo("66.67");   // 100 − 33.33, not 66.67 rounded independently
        assertThat(rows.stream().map(AccProfitAllocation::getAllocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(rows).allSatisfy(r -> assertThat(r.getAllocStatus()).isEqualTo("DRAFT"));
    }

    @Test void capitalRatioRecordsAdditionsAndWithdrawalsInsideThePeriod() {
        statement(1L, "1000", new Object[][]{{LocalDate.of(2026, 9, 10), "500", "0"}, {LocalDate.of(2026, 9, 20), "0", "300"}});
        statement(2L, "0", new Object[][]{{LocalDate.of(2026, 9, 15), "800", "0"}});
        service.allocate(dist, policy("CAPITAL_RATIO"), new BigDecimal("2000"));
        List<AccProfitAllocation> rows = saved();
        assertThat(rows.get(0).getOpeningCapital()).isEqualByComparingTo("1000");
        assertThat(rows.get(0).getAdditions()).isEqualByComparingTo("500");
        assertThat(rows.get(0).getWithdrawals()).isEqualByComparingTo("300");
        assertThat(rows.get(0).getEligibleCapital()).isEqualByComparingTo("1200");   // closing
        assertThat(rows.get(1).getEligibleCapital()).isEqualByComparingTo("800");
        assertThat(rows.get(0).getAllocatedAmount()).isEqualByComparingTo("1200.00");   // 1200/2000 × 2000
        assertThat(rows.get(1).getAllocatedAmount()).isEqualByComparingTo("800.00");
    }

    @Test void weightedAverageCapitalCountsMoneyForTheDaysItWasIn() {
        // A: 1000 all 30 days → average 1000. B: 0 for 14 days, then 3000 for the last 16 days → 48000/30 = 1600.
        statement(1L, "1000", new Object[][]{});
        statement(2L, "0", new Object[][]{{LocalDate.of(2026, 9, 15), "3000", "0"}});
        service.allocate(dist, policy("WEIGHTED_AVERAGE_CAPITAL"), new BigDecimal("2600"));
        List<AccProfitAllocation> rows = saved();
        assertThat(rows.get(0).getEligibleCapital()).isEqualByComparingTo("1000.00");
        assertThat(rows.get(1).getEligibleCapital()).isEqualByComparingTo("1600.00");
        assertThat(rows.get(0).getAllocatedAmount()).isEqualByComparingTo("1000.00");
        assertThat(rows.get(1).getAllocatedAmount()).isEqualByComparingTo("1600.00");
    }

    @Test void investorSpecificPercentagesComeFromThePolicyLinesAndNeedNotUseTheWholePool() {
        statement(1L, "1000", new Object[][]{});
        statement(2L, "1000", new Object[][]{});
        when(policyLineRepo.findByPidProfitPolicy(5L)).thenReturn(List.of(
                AccProfitPolicyLine.builder().pidInvestor(1L).sharePct(new BigDecimal("50")).build(),
                AccProfitPolicyLine.builder().pidInvestor(2L).sharePct(new BigDecimal("25")).build()));
        service.allocate(dist, policy("INVESTOR_SPECIFIC_PERCENTAGE"), new BigDecimal("1000"));
        List<AccProfitAllocation> rows = saved();
        assertThat(rows.get(0).getAllocatedAmount()).isEqualByComparingTo("500.00");
        assertThat(rows.get(1).getAllocatedAmount()).isEqualByComparingTo("250.00");   // 25% stays unallocated, no remainder top-up
    }

    @Test void customApprovedMethodUsesEachInvestorsOwnSharePct() {
        statement(1L, "0", new Object[][]{});
        statement(2L, "0", new Object[][]{});
        service.allocate(dist, policy("CUSTOM_APPROVED_METHOD"), new BigDecimal("1000"));
        List<AccProfitAllocation> rows = saved();
        assertThat(rows.get(0).getAllocatedAmount()).isEqualByComparingTo("600.00");
        assertThat(rows.get(1).getAllocatedAmount()).isEqualByComparingTo("400.00");
    }

    @Test void percentagesAddingToMoreThanThePoolAreRefused() {
        statement(1L, "0", new Object[][]{});
        statement(2L, "0", new Object[][]{});
        when(policyLineRepo.findByPidProfitPolicy(5L)).thenReturn(List.of(
                AccProfitPolicyLine.builder().pidInvestor(1L).sharePct(new BigDecimal("70")).build(),
                AccProfitPolicyLine.builder().pidInvestor(2L).sharePct(new BigDecimal("40")).build()));
        assertThatThrownBy(() -> service.allocate(dist, policy("INVESTOR_SPECIFIC_PERCENTAGE"), new BigDecimal("1000")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exceed");
    }

    @Test void nobodyWithCapitalMeansNothingIsAllocatedOnARatioMethod() {
        statement(1L, "0", new Object[][]{});
        statement(2L, "0", new Object[][]{});
        service.allocate(dist, policy("CAPITAL_RATIO"), new BigDecimal("1000"));
        assertThat(saved()).allSatisfy(r -> { assertThat(r.getSharePct()).isEqualByComparingTo("0"); assertThat(r.getAllocatedAmount()).isEqualByComparingTo("0"); });
    }

    @Test void noActiveInvestorIsAnError() {
        when(investorRepo.findByPidOrgOrderByInvestorCodeAsc(ORG)).thenReturn(List.of(gone));
        assertThatThrownBy(() -> service.allocate(dist, policy("CAPITAL_RATIO"), BigDecimal.TEN)).isInstanceOf(BusinessException.class).hasMessageContaining("No active investor");
    }

    @Test void aBranchDistributionOnlyIncludesThatBranchsInvestorsAndUnscopedOnes() {
        AccInvestor other = AccInvestor.builder().pidInvestor(4L).pidOrg(ORG).pidBranch(9L).investorCode("D").investorName("Other branch").investorStatus("ACTIVE").build();
        AccInvestor mine = AccInvestor.builder().pidInvestor(5L).pidOrg(ORG).pidBranch(2L).investorCode("E").investorName("This branch").investorStatus("ACTIVE").build();
        when(investorRepo.findByPidOrgOrderByInvestorCodeAsc(ORG)).thenReturn(List.of(a, other, mine));
        statement(1L, "100", new Object[][]{}); statement(5L, "100", new Object[][]{});
        AccProfitDistribution branchDist = AccProfitDistribution.builder().pidDistribution(11L).pidOrg(ORG).pidBranch(2L).periodFrom(FROM).periodTo(TO).build();
        service.allocate(branchDist, policy("CAPITAL_RATIO"), new BigDecimal("100"));
        assertThat(saved().stream().map(AccProfitAllocation::getPidInvestor)).containsExactly(1L, 5L);
    }
}
