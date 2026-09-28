package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AclModuleInfo;
import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.repository.AclModuleInfoRepository;
import com.friendspharma.app.repository.AclUserRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Asks the permission matrix whether the signed-in user may do something.
 *
 * <p><b>Why this exists.</b> Sensitive actions were gated on a hard-coded role —
 * {@code hasRole("ROLE01")} in an annotation, or {@code SecurityUtils.hasRole}
 * inside a service. That meant the RBAC screen could show a right ticked while
 * the server refused it, which is exactly what happened with purchase approval:
 * Branch Manager held CAN_APPROVE on Purchase Invoice, the Purchase screen read
 * that grant and offered "Save &amp; Approve", and the server then answered 403.
 * Changing who may approve also meant changing Java and redeploying.
 *
 * <p>Now the tick <em>is</em> the permission. Granting a right on the RBAC screen
 * grants it in the application, for any role, with no code change.
 *
 * <p><b>It resolves through all three layers</b>, via {@link PermissionResolver}:
 * the role grant, narrowed by what the pharmacy's subscription allows, narrowed
 * again by anything set on the individual user. A role may not hand out a right
 * the pharmacy has not bought.
 *
 * <p><b>There is no super-user shortcut.</b> Super Admin passes these checks
 * because the matrix grants it the right, not because the code names the role —
 * so the matrix is the whole truth, and reading it tells you who may act.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionGuard {

    private final PermissionResolver  resolver;
    private final AclUserRepository   userRepo;
    private final AclModuleInfoRepository moduleRepo;

    /** The rights a module can carry, named as the RBAC screen labels them. */
    public enum Right { VIEW, CREATE, EDIT, DELETE, APPROVE, EXPORT, PRINT }

    /**
     * Whether the signed-in user holds {@code right} on {@code moduleCode}.
     *
     * <p>False when nobody is signed in, when the module does not exist, or when
     * the user has no grant on it — an absent grant is not a right.
     */
    @Transactional(readOnly = true)
    public boolean has(String moduleCode, Right right) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return false;

        AclUser user = userRepo.findById(userId).orElse(null);
        if (user == null) return false;

        AclModuleInfo module = moduleRepo.findByModuleCode(moduleCode).orElse(null);
        if (module == null) {
            // A missing module is a configuration fault, not a permission
            // decision. Say so loudly rather than silently denying everyone.
            log.error("Permission checked against unknown module '{}' — denying.", moduleCode);
            return false;
        }

        Map<Long, PermissionResolver.Rights> rights = resolver.resolve(user);
        PermissionResolver.Rights r = rights.get(module.getPidModule());
        if (r == null) return false;

        return switch (right) {
            case VIEW    -> r.view()    == 1;
            case CREATE  -> r.create()  == 1;
            case EDIT    -> r.edit()    == 1;
            case DELETE  -> r.delete()  == 1;
            case APPROVE -> r.approve() == 1;
            case EXPORT  -> r.export()  == 1;
            case PRINT   -> r.print()   == 1;
        };
    }

    /**
     * As {@link #has(String, Right)} for the signed-in user, but ignoring the
     * plan's right-level narrowing — see
     * {@link PermissionResolver#resolveIgnoringPlan}.
     *
     * <p>For rights that describe the person rather than the product: whether a
     * pharmacy's own administrator may see that pharmacy's takings is a fact
     * about their job. Reading it through the plan meant a role holding
     * CAN_APPROVE on the Dashboard was refused because the pharmacy's grant row
     * happened to carry a zero — a default from the template it was created
     * from, not a decision anybody made about money.
     *
     * <p>Module level still applies: a screen the pharmacy does not have stays
     * out, whoever asks.
     */
    @Transactional(readOnly = true)
    public boolean hasByRole(String moduleCode, Right right) {
        return has(SecurityUtils.getCurrentUserId(), moduleCode, right, false);
    }

    /** As {@link #has} but for a named user rather than the signed-in one. */
    @Transactional(readOnly = true)
    public boolean has(Long userId, String moduleCode, Right right) {
        return has(userId, moduleCode, right, true);
    }

    /**
     * As {@link #has(Long, String, Right)} but ignoring the plan's right-level
     * narrowing — the question is who this person is, not what their pharmacy
     * bought. See {@link PermissionResolver#resolveIgnoringPlan}.
     *
     * <p>Reserved for authority to vouch for something, not for reaching a
     * feature. If a screen starts calling this, the screen is wrong.
     */
    @Transactional(readOnly = true)
    public boolean hasByRole(Long userId, String moduleCode, Right right) {
        return has(userId, moduleCode, right, false);
    }

    private boolean has(Long userId, String moduleCode, Right right, boolean planNarrows) {
        if (userId == null) return false;
        AclUser user = userRepo.findById(userId).orElse(null);
        if (user == null) return false;

        AclModuleInfo module = moduleRepo.findByModuleCode(moduleCode).orElse(null);
        if (module == null) {
            log.error("Permission checked against unknown module '{}' — denying.", moduleCode);
            return false;
        }

        Map<Long, PermissionResolver.Rights> all =
                planNarrows ? resolver.resolve(user) : resolver.resolveIgnoringPlan(user);
        PermissionResolver.Rights r = all.get(module.getPidModule());
        if (r == null) return false;

        return switch (right) {
            case VIEW    -> r.view()    == 1;
            case CREATE  -> r.create()  == 1;
            case EDIT    -> r.edit()    == 1;
            case DELETE  -> r.delete()  == 1;
            case APPROVE -> r.approve() == 1;
            case EXPORT  -> r.export()  == 1;
            case PRINT   -> r.print()   == 1;
        };
    }
}
