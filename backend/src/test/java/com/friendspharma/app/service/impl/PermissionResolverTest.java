package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AclModuleInfo;
import com.friendspharma.app.entity.AclTenantModule;
import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.entity.AclUserPermission;
import com.friendspharma.app.entity.PosPermissions;
import com.friendspharma.app.repository.AclTenantModuleRepository;
import com.friendspharma.app.repository.AclUserPermissionRepository;
import com.friendspharma.app.repository.PosPermissionsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * What a person may actually do, once role, plan and exception have all had
 * their say.
 *
 * <p>This is the class the sidebar and every endpoint ask, so a disagreement
 * between it and the screen that grants rights is invisible until somebody is
 * refused something they were told they had. These pin that agreement.
 *
 * <p>The case that prompted them: a pharmacy on the Basic template has Approve
 * withheld by its plan while the role grants it. Approve must be grantable to
 * one named person, and must survive the trip through here.
 */
@ExtendWith(MockitoExtension.class)
class PermissionResolverTest {

    private static final long ROLE = 2L, BRANCH = 45L, USER = 7L;
    private static final long SALES = 4L, POS = 5L, HR = 16L;

    @Mock  private PosPermissionsRepository    roleRepo;
    @Mock  private AclTenantModuleRepository   tenantRepo;
    @Mock  private AclUserPermissionRepository userRepo;
    @InjectMocks private PermissionResolver resolver;

    /* ── builders ─────────────────────────────────────────────────────────── */

    private static AclUser user() {
        AclUser u = new AclUser();
        u.setPidUser(USER);
        u.setPidGroup(ROLE);
        u.setPidBranch(BRANCH);
        return u;
    }

    /** A role grant. `approve` is the right under test; the rest are open. */
    private static PosPermissions role(long moduleId, int approve) {
        AclModuleInfo m = new AclModuleInfo();
        m.setPidModule(moduleId);
        return PosPermissions.builder()
                .module(m)
                .canView(1).canCreate(1).canEdit(1).canDelete(1)
                .canApprove(approve).canExport(1).canPrint(1)
                .build();
    }

    /** What the pharmacy has by default. */
    private static AclTenantModule plan(long moduleId, int approve) {
        return AclTenantModule.builder()
                .pidBranch(BRANCH).pidModule(moduleId)
                .canView(1).canCreate(1).canEdit(1).canDelete(1)
                .canApprove(approve).canExport(1).canPrint(1)
                .build();
    }

    /** One person's exception. Null on a right means "follow the default". */
    private static AclUserPermission override(long moduleId, Integer approve) {
        return AclUserPermission.builder()
                .pidUser(USER).pidModule(moduleId).canApprove(approve).build();
    }

    private void given(List<PosPermissions> roles,
                       List<AclTenantModule> plans,
                       List<AclUserPermission> overrides) {
        when(roleRepo.findByRole_PidGroup(ROLE)).thenReturn(roles);
        when(tenantRepo.findByPidBranch(BRANCH)).thenReturn(plans);
        when(userRepo.findByPidUser(USER)).thenReturn(overrides);
    }

    private int approveOn(long moduleId) {
        Map<Long, PermissionResolver.Rights> out = resolver.resolve(user());
        PermissionResolver.Rights r = out.get(moduleId);
        return r == null ? -1 : r.approve();
    }

    /* ── the plan as a default ────────────────────────────────────────────── */

    @Nested
    @DisplayName("the plan decides what everyone starts with")
    class PlanIsTheDefault {

        @Test
        @DisplayName("no exception: the plan narrows the role, as it always did")
        void planNarrowsByDefault() {
            given(List.of(role(SALES, 1)), List.of(plan(SALES, 0)), List.of());
            assertThat(approveOn(SALES))
                    .as("role grants Approve, plan withholds it, nobody decided otherwise")
                    .isZero();
        }

        @Test
        @DisplayName("no plan rows at all means unrestricted, not forbidden")
        void absentPlanIsUnrestricted() {
            given(List.of(role(SALES, 1)), List.of(), List.of());
            assertThat(approveOn(SALES)).isOne();
        }
    }

    /* ── the change ───────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("one person can be set above the plan")
    class RaisedAbovePlan {

        /**
         * The regression this suite exists for. The override used to be stored
         * and displayed, then narrowed away here, so the grant simply did not
         * happen and nothing said so.
         */
        @Test
        @DisplayName("an exception grants Approve the plan withholds")
        void overrideRaisesAbovePlan() {
            given(List.of(role(SALES, 1)), List.of(plan(SALES, 0)),
                  List.of(override(SALES, 1)));
            assertThat(approveOn(SALES))
                    .as("the plan is a default; the role grants Approve, so it must stick")
                    .isOne();
        }

        /**
         * The role supplies the default, not a limit. An administrator who has
         * decided this person may approve has decided it — the alternative is
         * accepting the tick and discarding it, which is how this screen came to
         * need fixing three times.
         */
        @Test
        @DisplayName("and above the role too, once somebody has decided it")
        void overrideRaisesAboveRole() {
            given(List.of(role(SALES, 0)), List.of(plan(SALES, 1)),
                  List.of(override(SALES, 1)));
            assertThat(approveOn(SALES)).isOne();
        }

        @Test
        @DisplayName("a module the role does not carry at all still resolves")
        void moduleAbsentFromRole() {
            given(List.of(role(SALES, 1)), List.of(plan(POS, 1)),
                  List.of(override(POS, 1)));
            assertThat(approveOn(POS))
                    .as("resolving only the role's own modules would store this "
                        + "decision and then never act on it")
                    .isOne();
        }

        @Test
        @DisplayName("and can still take a right away")
        void stillNarrows() {
            given(List.of(role(POS, 1)), List.of(plan(POS, 1)),
                  List.of(override(POS, 0)));
            assertThat(approveOn(POS)).isZero();
        }

        @Test
        @DisplayName("a null right keeps following the default rather than clearing it")
        void nullFollowsDefault() {
            given(List.of(role(SALES, 1)), List.of(plan(SALES, 1)),
                  List.of(override(SALES, null)));
            assertThat(approveOn(SALES)).isOne();
        }
    }

    /* ── authority to vouch, as opposed to reaching a feature ─────────────── */

    @Nested
    @DisplayName("resolveIgnoringPlan — who somebody is, not what they bought")
    class IgnoringPlan {

        private int approveIgnoringPlan(long moduleId) {
            PermissionResolver.Rights r = resolver.resolveIgnoringPlan(user()).get(moduleId);
            return r == null ? -1 : r.approve();
        }

        /**
         * The invoice-unlock case. An Administrator (Branch) at a pharmacy on the
         * Basic template holds Approve on Quick Sales in RBAC and was told they
         * were "not an administrator" — so that pharmacy had nobody who could
         * authorise a correction.
         */
        @Test
        @DisplayName("the plan does not withhold authority the role grants")
        void planDoesNotRemoveAuthority() {
            given(List.of(role(POS, 1)), List.of(plan(POS, 0)), List.of());
            assertThat(approveIgnoringPlan(POS)).isOne();
        }

        @Test
        @DisplayName("but the role still has to grant it")
        void roleMustStillGrantIt() {
            given(List.of(role(POS, 0)), List.of(plan(POS, 1)), List.of());
            assertThat(approveIgnoringPlan(POS)).isZero();
        }

        @Test
        @DisplayName("and a decision about this person still wins")
        void overrideStillWins() {
            given(List.of(role(POS, 0)), List.of(plan(POS, 0)), List.of(override(POS, 1)));
            assertThat(approveIgnoringPlan(POS)).isOne();
        }

        @Test
        @DisplayName("a module the pharmacy does not have stays out")
        void moduleLevelStillApplies() {
            given(List.of(role(HR, 1)), List.of(plan(POS, 1)), List.of());
            assertThat(approveIgnoringPlan(HR))
                    .as("no invoices exist on a module the pharmacy never bought")
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("the ordinary resolve still narrows by the plan")
        void ordinaryResolveUnchanged() {
            given(List.of(role(POS, 1)), List.of(plan(POS, 0)), List.of());
            assertThat(approveOn(POS))
                    .as("only the vouching path skips the plan")
                    .isZero();
        }
    }

    /* ── the boundary that stays absolute ─────────────────────────────────── */

    @Nested
    @DisplayName("module level is not negotiable")
    class ModuleLevelStaysHard {

        @Test
        @DisplayName("a module the pharmacy never bought is dropped, exception or not")
        void unsoldModuleStaysOut() {
            given(List.of(role(HR, 1)), List.of(plan(SALES, 1)),
                  List.of(override(HR, 1)));
            assertThat(approveOn(HR))
                    .as("naming one person must not hand a Basic subscriber a screen "
                        + "the pharmacy never bought")
                    .isEqualTo(-1);
        }
    }
}
