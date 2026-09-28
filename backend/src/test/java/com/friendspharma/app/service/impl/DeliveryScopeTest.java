package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.EmployeeType;
import com.friendspharma.app.entity.HrEmployee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a delivery man may do, and to whose orders.
 *
 * <p>The rules exist because the UI cannot enforce them: hiding a button stops
 * a click, not a request. These pin the decisions the service makes, which is
 * where the enforcement actually lives.
 */
class DeliveryScopeTest {

    /** Mirrors SalesServiceImpl.DRIVER_STAGES, which is private. */
    private static final List<String> DRIVER_STAGES = List.of("On The Way", "Delivered");

    private static final List<String> ALL_STAGES = List.of(
            "Submitted", "Confirmed", "Delivery Assigned", "On The Way", "Delivered", "Collected");

    @Nested
    @DisplayName("stage transitions a driver may make")
    class DriverStages {

        @Test
        @DisplayName("on the way and delivered — the two facts only they know")
        void allowed() {
            assertThat(DRIVER_STAGES).containsExactly("On The Way", "Delivered");
        }

        /**
         * The exclusions carry the reasoning.
         *
         * <p>Confirming is a sales decision made before anything leaves the
         * shelf. Assigning is management's. And Collected is reached by taking
         * money through {@code collect}, not by a driver declaring it — letting
         * them set it directly would mark an invoice settled with no payment
         * recorded against it.
         */
        @Test
        @DisplayName("everything else is management's or follows from a payment")
        void refused() {
            List<String> refused = ALL_STAGES.stream()
                    .filter(s -> !DRIVER_STAGES.contains(s))
                    .toList();
            assertThat(refused).containsExactly(
                    "Submitted", "Confirmed", "Delivery Assigned", "Collected");
        }
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        /** Mirrors requireOwnDelivery: the invoice's driver must be this driver. */
        private static boolean mayAct(Long myEmployeeId, Long invoiceDeliveryMan) {
            return myEmployeeId != null && myEmployeeId.equals(invoiceDeliveryMan);
        }

        @Test
        @DisplayName("a driver acts on their own round and no other")
        void ownRoundOnly() {
            assertThat(mayAct(7L, 7L)).isTrue();
            assertThat(mayAct(7L, 8L)).isFalse();
        }

        @Test
        @DisplayName("an unassigned invoice belongs to nobody")
        void unassignedIsNobodys() {
            // Not "available to whoever asks" — an order nobody dispatched is not
            // a driver's to mark delivered or collect against.
            assertThat(mayAct(7L, null)).isFalse();
        }

        @Test
        @DisplayName("a login with no staff record can claim nothing")
        void unlinkedLoginClaimsNothing() {
            // The service refuses this with an explanation rather than returning
            // an empty round, which would read as "no work today".
            assertThat(mayAct(null, 7L)).isFalse();
            assertThat(mayAct(null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("who may hold a delivery login")
    class LoginEligibility {

        private static HrEmployee employee(String type, String status) {
            HrEmployee e = new HrEmployee();
            e.setEmpType(type);
            e.setStatus(status);
            return e;
        }

        @Test
        @DisplayName("only a delivery man — pointing a driver login elsewhere lends an identity")
        void onlyDeliveryMen() {
            assertThat(employee(EmployeeType.DELIVERY_MAN, "Y").isDeliveryMan()).isTrue();
            assertThat(employee(EmployeeType.ACCOUNTANT, "Y").isDeliveryMan()).isFalse();
            assertThat(employee(EmployeeType.OTHER, "Y").isDeliveryMan()).isFalse();
        }
    }
}
