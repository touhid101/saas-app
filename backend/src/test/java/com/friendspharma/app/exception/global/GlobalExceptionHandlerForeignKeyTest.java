package com.friendspharma.app.exception.global;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The foreign-key message names the record and the constraint Postgres reported. */
class GlobalExceptionHandlerForeignKeyTest {

    @Test
    void missingParentNamesTheKeyAndTable() {
        String pg = "ERROR: insert or update on table \"pos_supplier_payment\" violates foreign key constraint \"fk_supplier_payment_sup\"\n"
                  + "  Detail: Key (pid_supplier)=(901) is not present in table \"pos_supplier_info\".";
        assertEquals("Cannot complete operation: it refers to supplier 901 that does not exist in pos_supplier_info. (fk_supplier_payment_sup)",
                GlobalExceptionHandler.foreignKeyMessage(pg));
    }

    @Test
    void stillReferencedNamesTheDependentTable() {
        String pg = "ERROR: update or delete on table \"pos_stock_batch\" violates foreign key constraint \"fk_movement_batch\" on table \"pos_stock_movement\"\n"
                  + "  Detail: Key (pid_stock)=(12) is still referenced from table \"pos_stock_movement\".";
        assertEquals("Cannot complete operation: stock 12 is still used by records in pos_stock_movement and cannot be removed. (fk_movement_batch)",
                GlobalExceptionHandler.foreignKeyMessage(pg));
    }

    @Test
    void unknownShapeKeepsTheGenericSentence() {
        assertEquals("Cannot complete operation: referenced record does not exist or is in use by other records.",
                GlobalExceptionHandler.foreignKeyMessage("something about a foreign key"));
    }
}
