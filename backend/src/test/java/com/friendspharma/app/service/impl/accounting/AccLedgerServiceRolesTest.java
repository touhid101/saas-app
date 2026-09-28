package com.friendspharma.app.service.impl.accounting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A statement may span several roles; the party's primary role fixes which side the balance is read from. */
class AccLedgerServiceRolesTest {

    @Test
    void splitsAndTrimsRoleLists() {
        assertEquals(List.of("AP", "SUPPLIER_ADVANCE"), AccLedgerService.roles("AP, SUPPLIER_ADVANCE"));
        assertEquals(List.of("AR"), AccLedgerService.roles("AR"));
    }

    @Test
    void mixedSupplierStatementReadsFromTheCreditSide() {
        assertEquals("C", AccLedgerService.sideOf("AP,SUPPLIER_ADVANCE"));
    }

    @Test
    void mixedCustomerStatementReadsFromTheDebitSide() {
        assertEquals("D", AccLedgerService.sideOf("AR,CUSTOMER_ADVANCE"));
    }

    @Test
    void singleRoleKeepsTheAccountsOwnSide() {
        assertNull(AccLedgerService.sideOf("AP"));
        assertNull(AccLedgerService.sideOf("INVESTOR_CAPITAL"));
        assertNull(AccLedgerService.sideOf(null));
    }
}
