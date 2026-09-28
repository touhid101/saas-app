package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a module with no route means.
 *
 * <p>Two screens read {@code ACL_MODULE_INFO} and they disagreed about it.
 *
 * <ul>
 *   <li>The role matrix called a row a heading when it had children <b>and</b>
 *       no route, so a route-less row with nothing beneath it was grantable.</li>
 *   <li>The per-user permission screen skipped anything with no route at all, so
 *       the same row was invisible there.</li>
 * </ul>
 *
 * <p>Nothing exercised the disagreement until Notifications — a capability with
 * a permission but no page — became the first module of that shape. Under the
 * old rule it could be granted to a role and never to a person, and the menu
 * would have offered it as a link to a page that does not exist.
 *
 * <p>The settled rule, pinned here: a route-less module <b>with</b> children is
 * a heading; a route-less module <b>without</b> children is a capability. A
 * capability is grantable on both screens and appears in neither menu.
 *
 * <p>The rule itself lives in {@link ModuleShape}, which both screens call, so
 * these exercise the shipped code rather than a copy of it. Only the "before"
 * predicate is a model — kept so the tests can show the old behaviour failing
 * rather than merely asserting the new one. No Spring context: what is being
 * fixed is one boolean, and a test needing a database would not be checking it.
 */
class CapabilityModuleTest {

    /** A module, reduced to the two things these decisions turn on. */
    private record Module(String code, String routePath, boolean hasChildren) {
        boolean routeless() { return routePath == null || routePath.isBlank(); }
    }

    private static final Module PAGE       = new Module("MOD13", "/inventory", false);
    private static final Module HEADING    = new Module("GRP_SALES", null, true);
    private static final Module CAPABILITY = new Module("MOD28", null, false);

    /* ── the predicates ───────────────────────────────────────────────────
       Two of these call ModuleShape, which is the code both screens now run.
       The third is the rule as it stood before, kept so the tests can show the
       old behaviour failing rather than merely asserting the new one. */

    /** RBAC.jsx, mirrored: it already used this definition. */
    private static boolean roleMatrixTreatsAsHeading(Module m) {
        return ModuleShape.isHeading(m.routePath(), m.hasChildren());
    }

    /** TenantOnboardingService.userPermissions — what it used to skip. */
    private static boolean perUserScreenSkippedBefore(Module m) {
        return m.routeless();
    }

    /** TenantOnboardingService.userPermissions, as it now runs. */
    private static boolean perUserScreenSkips(Module m) {
        return ModuleShape.isHeading(m.routePath(), m.hasChildren());
    }

    /** PermissionServiceImpl.buildMenuForUser, as it now runs. */
    private static boolean menuSkips(Module m) {
        return ModuleShape.isCapability(m.routePath(), m.hasChildren());
    }

    @Nested
    @DisplayName("a capability: no route, nothing beneath it")
    class Capability {

        @Test
        @DisplayName("used to be ungrantable on the per-user screen")
        void wasInvisibleBefore() {
            assertThat(perUserScreenSkippedBefore(CAPABILITY)).isTrue();
        }

        @Test
        @DisplayName("is grantable on the per-user screen")
        void isGrantablePerUser() {
            assertThat(perUserScreenSkips(CAPABILITY)).isFalse();
        }

        @Test
        @DisplayName("is grantable on the role matrix, as it always was")
        void isGrantableByRole() {
            assertThat(roleMatrixTreatsAsHeading(CAPABILITY)).isFalse();
        }

        /** Without this it renders as a nav link to a page that does not exist. */
        @Test
        @DisplayName("never appears in the menu")
        void isNotInTheMenu() {
            assertThat(menuSkips(CAPABILITY)).isTrue();
        }
    }

    @Nested
    @DisplayName("a heading: no route, children beneath it")
    class Heading {

        @Test
        @DisplayName("is still not grantable on either screen")
        void staysUngrantable() {
            assertThat(perUserScreenSkips(HEADING)).isTrue();
            assertThat(roleMatrixTreatsAsHeading(HEADING)).isTrue();
        }

        /** It is the heading the menu is built from, so it must survive. */
        @Test
        @DisplayName("is not dropped from the menu")
        void survivesInTheMenu() {
            assertThat(menuSkips(HEADING)).isFalse();
        }
    }

    @Nested
    @DisplayName("an ordinary page")
    class Page {

        @Test
        @DisplayName("is grantable everywhere and stays in the menu")
        void unaffected() {
            assertThat(perUserScreenSkips(PAGE)).isFalse();
            assertThat(roleMatrixTreatsAsHeading(PAGE)).isFalse();
            assertThat(menuSkips(PAGE)).isFalse();
        }

        /** The change must not have moved anything for the 29 existing pages. */
        @Test
        @DisplayName("is treated exactly as it was before")
        void behaviourIsUnchanged() {
            assertThat(perUserScreenSkips(PAGE)).isEqualTo(perUserScreenSkippedBefore(PAGE));
        }
    }
}
