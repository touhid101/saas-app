package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AclTenantModule;
import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.entity.AclUserPermission;
import com.friendspharma.app.entity.PosPermissions;
import com.friendspharma.app.repository.AclTenantModuleRepository;
import com.friendspharma.app.repository.AclUserPermissionRepository;
import com.friendspharma.app.repository.PosPermissionsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What one person may actually do, once the role, the plan and their own
 * exceptions have all had their say.
 *
 * <p>Three layers, resolved in one place so that the sidebar, the API and any
 * future check cannot disagree about the answer:
 *
 * <pre>
 *   role    what the job allows        POS_PERMISSIONS       the default
 *   tenant  what the pharmacy has      ACL_TENANT_MODULE     narrows the default
 *   user    what was decided for them  ACL_USER_PERMISSION   the answer
 * </pre>
 *
 * <p><b>Module level is absolute; right level is a default.</b> A module the
 * pharmacy never bought is dropped outright, so a Basic subscriber cannot be
 * handed an Enterprise screen by naming one person. That is the only limit here
 * that is not a default.
 *
 * <p><b>A decision about a named person is the answer, and nothing narrows it
 * afterwards.</b> Role and plan settle what somebody would have had if nobody
 * looked at them individually; once an administrator has, that is what applies.
 *
 * <p>The clamp that used to live here is gone on purpose. Every version of it
 * disagreed with the screen that writes overrides, so a grant was accepted,
 * stored, shown as granted — and then discarded at the moment it mattered, with
 * nothing anywhere saying so. A permission model that is merely strict is worth
 * less than one that is honest about what it did.
 *
 * <p><b>An absent grant means unrestricted, not forbidden.</b> A branch with no
 * rows in {@code ACL_TENANT_MODULE} gets exactly what its roles allow. That is
 * what let this ship without stopping every branch already trading on the day it
 * landed, and it stays true for any branch an administrator creates by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionResolver {

    private final PosPermissionsRepository    roleRepo;
    private final AclTenantModuleRepository   tenantRepo;
    private final AclUserPermissionRepository userRepo;

    /** The seven rights, resolved for one module. */
    public record Rights(int view, int create, int edit, int delete,
                         int approve, int export, int print) {

        public static final Rights NONE = new Rights(0, 0, 0, 0, 0, 0, 0);

        public boolean canView() { return view == 1; }
        public boolean any() {
            return view + create + edit + delete + approve + export + print > 0;
        }

        /** The narrower of two grants, right by right. */
        Rights narrowedBy(Rights other) {
            if (other == null) return this;
            return new Rights(
                    Math.min(view, other.view),       Math.min(create, other.create),
                    Math.min(edit, other.edit),       Math.min(delete, other.delete),
                    Math.min(approve, other.approve), Math.min(export, other.export),
                    Math.min(print, other.print));
        }
    }

    private static int flag(Integer v) { return v != null && v == 1 ? 1 : 0; }

    /**
     * Every module this user has any right to, and what those rights are.
     *
     * <p>Three queries regardless of how many modules exist — the alternative is
     * one per module, which on a twenty-four module menu is how a sidebar comes
     * to take a second to draw.
     */
    public Map<Long, Rights> resolve(AclUser user) {
        return resolve(user, true);
    }

    /**
     * The same answer, with the plan's right-level narrowing left out.
     *
     * <p>For questions about <b>who somebody is</b> rather than what the pharmacy
     * bought. Authorising a correction to a saved invoice is the case: whether a
     * branch administrator is senior enough to vouch for one is a fact about
     * their job, and letting a subscription tier decide it means a pharmacy on
     * the cheaper plan has nobody who can correct an invoice at all. That is not
     * a feature being withheld, it is a control that cannot be exercised.
     *
     * <p>Module level still applies. A pharmacy without the module has no
     * invoices on it to correct, so the question does not arise.
     *
     * <p>Use {@link #resolve} for everything else. This is the exception, and it
     * should stay one.
     */
    public Map<Long, Rights> resolveIgnoringPlan(AclUser user) {
        return resolve(user, false);
    }

    private Map<Long, Rights> resolve(AclUser user, boolean planNarrows) {
        if (user == null || user.getPidGroup() == null) return Map.of();

        Map<Long, Rights> role = roleRepo.findByRole_PidGroup(user.getPidGroup()).stream()
                .collect(Collectors.toMap(p -> p.getModule().getPidModule(), PermissionResolver::fromRole));

        // Absent means unrestricted. An empty map here must not be read as
        // "this pharmacy may do nothing" — see the class note.
        List<AclTenantModule> grantRows = user.getPidBranch() == null
                ? List.of() : tenantRepo.findByPidBranch(user.getPidBranch());
        Map<Long, Rights> tenant = grantRows.stream()
                .collect(Collectors.toMap(AclTenantModule::getPidModule, PermissionResolver::fromTenant,
                        (a, b) -> a));
        boolean tenantRestricted = !tenant.isEmpty();

        Map<Long, AclUserPermission> overrides = userRepo.findByPidUser(user.getPidUser()).stream()
                .collect(Collectors.toMap(AclUserPermission::getPidModule, Function.identity(), (a, b) -> a));

        /* Both sets, not just the role's. A person can be given a module their
           role does not carry, and looking only at the role would store that
           decision and then never act on it. */
        Set<Long> candidates = new HashSet<>(role.keySet());
        candidates.addAll(overrides.keySet());

        Map<Long, Rights> out = new HashMap<>();
        for (Long moduleId : candidates) {
            Rights effective = role.getOrDefault(moduleId, Rights.NONE);

            if (tenantRestricted) {
                Rights bought = tenant.get(moduleId);
                // Not in the plan at all: nothing to narrow to. Module level
                // remains a hard boundary — a pharmacy cannot be given a screen
                // it did not buy by naming one person — and it holds even when
                // the plan's right-level narrowing is being skipped.
                if (bought == null) continue;
                if (planNarrows) effective = effective.narrowedBy(bought);
            }

            AclUserPermission override = overrides.get(moduleId);
            if (override != null) {
                /* An explicit decision about this person wins. Role and plan
                   decide what they would have had; a right set for them by name
                   is the answer.

                   Nothing narrows it afterwards, deliberately. Every previous
                   version of this line clamped the override to something the
                   screen did not clamp it to, so a grant was accepted, stored,
                   displayed — and then quietly discarded here. */
                effective = applyOverride(effective, override);
            }

            if (effective.any()) out.put(moduleId, effective);
        }
        return out;
    }

    /** Null on a right means "leave it as the role and plan decided". */
    private static Rights applyOverride(Rights base, AclUserPermission o) {
        return new Rights(
                o.getCanView()    == null ? base.view()    : flag(o.getCanView()),
                o.getCanCreate()  == null ? base.create()  : flag(o.getCanCreate()),
                o.getCanEdit()    == null ? base.edit()    : flag(o.getCanEdit()),
                o.getCanDelete()  == null ? base.delete()  : flag(o.getCanDelete()),
                o.getCanApprove() == null ? base.approve() : flag(o.getCanApprove()),
                o.getCanExport()  == null ? base.export()  : flag(o.getCanExport()),
                o.getCanPrint()   == null ? base.print()   : flag(o.getCanPrint()));
    }

    private static Rights fromRole(PosPermissions p) {
        return new Rights(flag(p.getCanView()), flag(p.getCanCreate()), flag(p.getCanEdit()),
                flag(p.getCanDelete()), flag(p.getCanApprove()), flag(p.getCanExport()),
                flag(p.getCanPrint()));
    }

    private static Rights fromTenant(AclTenantModule t) {
        return new Rights(flag(t.getCanView()), flag(t.getCanCreate()), flag(t.getCanEdit()),
                flag(t.getCanDelete()), flag(t.getCanApprove()), flag(t.getCanExport()),
                flag(t.getCanPrint()));
    }
}
