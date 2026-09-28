package com.friendspharma.app.service.impl;

import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.SalesPurchaseReportRepository;
import com.friendspharma.app.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The base reports refuse anyone without View on the Reports menu, server-side, not only in the menu. */
class ReportGridServiceImplRightsTest {

    private MockedStatic<SecurityUtils> security;
    private final SalesPurchaseReportRepository repo = mock(SalesPurchaseReportRepository.class);
    private final HrReportServiceImpl hr = mock(HrReportServiceImpl.class);
    private final AccReportServiceImpl acc = mock(AccReportServiceImpl.class);
    private final PermissionGuard permissions = mock(PermissionGuard.class);
    private final ReportGridServiceImpl svc = new ReportGridServiceImpl(repo, permissions, hr, acc);

    @BeforeEach
    void up() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getCurrentOrgId).thenReturn(1L);
        security.when(SecurityUtils::isDeliveryStaff).thenReturn(false);
    }

    @AfterEach
    void down() { security.close(); }

    private static ReportGridServiceImpl.Filters filters() {
        return new ReportGridServiceImpl.Filters(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void refusesABaseReportWithoutTheReportsRight() {
        when(permissions.has(eq("MOD14"), any())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.run("sales-register", filters()));
        assertEquals("REPORT_NOT_PERMITTED", ex.getErrorCode());
        verifyNoInteractions(repo);
    }

    @Test
    void runsABaseReportWithTheRight() {
        when(permissions.has(eq("MOD14"), any())).thenReturn(true);
        when(repo.profitByCustomer(eq(1L), any(), any(), any(), eq(""))).thenReturn(List.of());
        assertEquals("customer-profit", svc.run("customer-profit", filters()).getId());
    }

    @Test
    void hrAndAccountsReportsAreGatedByTheirOwnEngines() {
        when(permissions.has(eq("MOD14"), any())).thenReturn(false);
        when(hr.run(eq("hr-headcount"), eq(1L), any())).thenReturn(ReportGridServiceImpl.grid("hr-headcount", "Headcount", List.of(), List.of(), null, List.of()));
        assertEquals("hr-headcount", svc.run("hr-headcount", filters()).getId());
    }

    @Test
    void unknownIdIsNamed() {
        when(permissions.has(eq("MOD14"), any())).thenReturn(true);
        assertThrows(ValidationException.class, () -> svc.run("no-such-report", filters()));
    }
}
