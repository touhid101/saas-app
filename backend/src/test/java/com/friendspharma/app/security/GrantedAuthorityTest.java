package com.friendspharma.app.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What authorities a signed-in user actually carries.
 *
 * <p>Written because a {@code @PreAuthorize} naming an authority nobody is ever
 * granted looks exactly like a working permission check, and fails only at the
 * moment somebody tries to use the feature. The requisition endpoints named
 * {@code MANAGER}; no code path issues it.
 *
 * <p>These pin the mapping {@link UserPrincipal#getAuthorities} performs, so an
 * authority named in an annotation can be checked against something.
 */
class GrantedAuthorityTest {

    private static final List<String> ALL_ROLES = List.of(
            "ROLE01", "ROLE02", "ROLE03", "ROLE04", "ROLE05",
            "ROLE06", "ROLE07", "ROLE08", "ROLE09");

    private static Set<String> authoritiesFor(String roleCode) {
        UserPrincipal p = UserPrincipal.builder()
                .userId(1L).username("u").password("x").status("Y")
                .roleCode(roleCode).superAdmin(false)
                .build();
        return p.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("what a role actually gets")
    class Mapping {

        @Test
        @DisplayName("Super Admin gets ROLE_ADMIN and every role code")
        void superAdminGetsEverything() {
            assertThat(authoritiesFor("ROLE01"))
                    .contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_ROLE01", "ROLE_ROLE09");
        }

        @Test
        @DisplayName("every other role gets only its own code")
        void othersGetOnlyTheirCode() {
            assertThat(authoritiesFor("ROLE02")).containsExactly("ROLE_ROLE02");
            assertThat(authoritiesFor("ROLE05")).containsExactly("ROLE_ROLE05");
        }
    }

    @Nested
    @DisplayName("the authorities the old requisition gate checked for")
    class TheBrokenGate {

        /**
         * The reason approving a requisition never worked for anyone but Super
         * Admin: nothing grants MANAGER, so half of
         * {@code hasAnyRole('ADMIN','MANAGER')} could never match.
         */
        @Test
        @DisplayName("ROLE_MANAGER is granted to nobody")
        void managerIsUnreachable() {
            for (String code : ALL_ROLES) {
                assertThat(authoritiesFor(code))
                        .as("%s must not somehow carry ROLE_MANAGER", code)
                        .doesNotContain("ROLE_MANAGER");
            }
        }

        @Test
        @DisplayName("ROLE_ADMIN reaches Super Admin and nobody else")
        void adminIsSuperAdminOnly() {
            assertThat(authoritiesFor("ROLE01")).contains("ROLE_ADMIN");
            for (String code : List.of("ROLE02", "ROLE03", "ROLE05", "ROLE09")) {
                assertThat(authoritiesFor(code))
                        .as("%s does not hold the ADMIN authority", code)
                        .doesNotContain("ROLE_ADMIN");
            }
        }

        /**
         * Taken together: the annotation that guarded approve, reject and convert
         * admitted exactly one role out of nine, whatever the RBAC screen showed.
         */
        @Test
        @DisplayName("so the old gate admitted one role out of nine")
        void theOldGateAdmittedOnlySuperAdmin() {
            long admitted = ALL_ROLES.stream()
                    .filter(c -> {
                        Set<String> a = authoritiesFor(c);
                        return a.contains("ROLE_ADMIN") || a.contains("ROLE_MANAGER");
                    })
                    .count();
            assertThat(admitted)
                    .as("only Super Admin could approve a requisition")
                    .isOne();
        }
    }
}
