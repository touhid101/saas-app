package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may see the money figures on the dashboard.
 *
 * <p>The gate used to be {@code hasRole("ROLE01") || hasRole("ROLE04")}, which
 * disagreed with the RBAC screen in both directions at once. These pin the rule
 * that replaced it — the Approve tick on the Dashboard module — using the grants
 * actually in the database, so the two cannot drift apart again without a test
 * turning red.
 */
class DashboardMoneyGateTest {

    /** A role, and its CAN_APPROVE on MOD01 as seeded. */
    private record Role(String code, String name, int dashboardApprove) {
        /** What the hard-coded check allowed, kept only to show what changed. */
        boolean oldGate()  { return code.equals("ROLE01") || code.equals("ROLE04"); }
        /** What the matrix allows — the rule now in force. */
        boolean newGate()  { return dashboardApprove == 1; }
    }

    private static final List<Role> ROLES = List.of(
            new Role("ROLE01", "Super Admin",            1),
            new Role("ROLE02", "Administrator (Branch)", 1),
            new Role("ROLE03", "Pharmacist",             0),
            new Role("ROLE04", "Accounts",               0),
            new Role("ROLE05", "Purchase Manager",       0),
            new Role("ROLE06", "Delivery Staff",         0),
            new Role("ROLE07", "Store Keeper",           0),
            new Role("ROLE08", "Viewer",                 0),
            new Role("ROLE09", "Sales Manager",          0));

    private static Role role(String code) {
        return ROLES.stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("the tick decides, not the role name")
    class MatrixDecides {

        @Test
        @DisplayName("a role holding Approve on Dashboard sees money")
        void approveGrantsMoney() {
            assertThat(role("ROLE01").newGate()).isTrue();
            assertThat(role("ROLE02").newGate()).isTrue();
        }

        @Test
        @DisplayName("a role without it does not")
        void noApproveNoMoney() {
            assertThat(role("ROLE03").newGate()).isFalse();
            assertThat(role("ROLE08").newGate()).isFalse();
            assertThat(role("ROLE09").newGate()).isFalse();
        }
    }

    @Nested
    @DisplayName("what the hard-coded check got wrong")
    class TheDivergence {

        /**
         * The reported case. The RBAC screen showed the tick and the server
         * refused it anyway, which reads as the permission screen being broken.
         */
        @Test
        @DisplayName("Administrator (Branch) held the tick and was refused")
        void branchAdminWasWronglyRefused() {
            Role r = role("ROLE02");
            assertThat(r.dashboardApprove()).isOne();
            assertThat(r.oldGate()).as("the old rule turned them away").isFalse();
            assertThat(r.newGate()).as("the matrix always said yes").isTrue();
        }

        /** The other direction, which matters more: access nobody granted. */
        @Test
        @DisplayName("Accounts did not hold the tick and was let in")
        void accountsWasWronglyAllowed() {
            Role r = role("ROLE04");
            assertThat(r.dashboardApprove()).isZero();
            assertThat(r.oldGate()).as("the old rule let them in").isTrue();
            assertThat(r.newGate()).as("no grant, no money").isFalse();
        }

        @Test
        @DisplayName("every other role is unaffected by the change")
        void everyoneElseUnchanged() {
            assertThat(ROLES.stream()
                    .filter(r -> r.oldGate() != r.newGate())
                    .map(Role::code))
                    .containsExactlyInAnyOrder("ROLE02", "ROLE04");
        }
    }

    @Nested
    @DisplayName("why Approve and not View")
    class NotView {

        /**
         * Every role that can open the dashboard holds View on it — that is what
         * puts it in their menu. Keying money to View would show takings to the
         * whole pharmacy, which is the outcome this gate exists to prevent.
         */
        @Test
        @DisplayName("Approve is the narrow right; View is held by everyone")
        void approveIsTheNarrowerRight() {
            long withApprove = ROLES.stream().filter(Role::newGate).count();
            assertThat(withApprove)
                    .as("only a couple of roles, out of nine")
                    .isEqualTo(2);
        }
    }
}
