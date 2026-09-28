package com.friendspharma.app.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The employee type is what decides who may be handed an invoice to deliver, so
 * the rules that turn a client's string into one are worth pinning.
 */
class EmployeeTypeTest {

    @Nested
    @DisplayName("normalise")
    class Normalise {

        @Test
        @DisplayName("accepts what a form is likely to send")
        void acceptsFormSpellings() {
            assertThat(EmployeeType.normalise("DELIVERY_MAN")).isEqualTo(EmployeeType.DELIVERY_MAN);
            assertThat(EmployeeType.normalise("delivery_man")).isEqualTo(EmployeeType.DELIVERY_MAN);
            assertThat(EmployeeType.normalise("Delivery Man")).isEqualTo(EmployeeType.DELIVERY_MAN);
            assertThat(EmployeeType.normalise("delivery-man")).isEqualTo(EmployeeType.DELIVERY_MAN);
            assertThat(EmployeeType.normalise("  Delivery Man  ")).isEqualTo(EmployeeType.DELIVERY_MAN);
        }

        /**
         * The case that matters most.
         *
         * <p>Falling back to OTHER would hide a client bug; falling back to
         * DELIVERY_MAN would put the wrong person on a delivery run. Null forces
         * the caller to decide, and every caller here turns it into a refusal.
         */
        @Test
        @DisplayName("an unrecognised type is null, never a fallback")
        void unknownIsNull() {
            assertThat(EmployeeType.normalise("DRIVER")).isNull();
            assertThat(EmployeeType.normalise("delivery")).isNull();
            assertThat(EmployeeType.normalise("DELIVERY_MEN")).isNull();
            assertThat(EmployeeType.normalise("' OR 1=1 --")).isNull();
        }

        @Test
        @DisplayName("blank and null are null")
        void blankIsNull() {
            assertThat(EmployeeType.normalise(null)).isNull();
            assertThat(EmployeeType.normalise("")).isNull();
            assertThat(EmployeeType.normalise("   ")).isNull();
        }

        @Test
        @DisplayName("every declared type round-trips through itself")
        void allRoundTrip() {
            for (String t : EmployeeType.ALL) {
                assertThat(EmployeeType.normalise(t)).isEqualTo(t);
                assertThat(EmployeeType.isValid(t)).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("label")
    class Label {
        @Test
        @DisplayName("reads as words, for messages and pickers")
        void spellsOutWords() {
            assertThat(EmployeeType.label(EmployeeType.DELIVERY_MAN)).isEqualTo("Delivery Man");
            assertThat(EmployeeType.label(EmployeeType.SALES_REP)).isEqualTo("Sales Rep");
            assertThat(EmployeeType.label(EmployeeType.OTHER)).isEqualTo("Other");
            assertThat(EmployeeType.label(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the set matches the CHECK constraint in V12")
    class ConstraintAgreement {
        /**
         * V12 constrains EMP_TYPE to exactly these seven values. If this list
         * grows without the migration growing with it, the new value passes
         * validation and then fails as a constraint violation at write time —
         * which surfaces as a 500, not a message anyone can act on.
         */
        @Test
        @DisplayName("seven values, in the order the migration lists them")
        void matchesMigration() {
            assertThat(EmployeeType.ALL).containsExactly(
                    "DELIVERY_MAN", "SALES_REP", "CASHIER",
                    "PHARMACIST", "MANAGER", "ACCOUNTANT", "OTHER");
        }
    }

    @Nested
    @DisplayName("HrEmployee eligibility")
    class Eligibility {

        private static HrEmployee employee(String type, String status) {
            HrEmployee e = new HrEmployee();
            e.setEmpType(type);
            e.setStatus(status);
            return e;
        }

        @Test
        @DisplayName("only an active delivery man can be assigned a delivery")
        void onlyActiveDeliveryMen() {
            assertThat(employee(EmployeeType.DELIVERY_MAN, "Y").isDeliveryMan()).isTrue();
            assertThat(employee(EmployeeType.DELIVERY_MAN, "Y").isActive()).isTrue();

            // The case the old code allowed: any employee id was accepted.
            assertThat(employee(EmployeeType.ACCOUNTANT, "Y").isDeliveryMan()).isFalse();
            assertThat(employee(EmployeeType.OTHER, "Y").isDeliveryMan()).isFalse();

            // Someone who has left.
            assertThat(employee(EmployeeType.DELIVERY_MAN, "N").isActive()).isFalse();
        }

        @Test
        @DisplayName("a new employee defaults to OTHER, which grants nothing")
        void defaultsToOther() {
            assertThat(new HrEmployee().getEmpType()).isEqualTo(EmployeeType.OTHER);
            assertThat(new HrEmployee().isDeliveryMan()).isFalse();
        }
    }
}
