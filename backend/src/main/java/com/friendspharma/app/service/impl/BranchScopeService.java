package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.security.ActingBranch;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Which branch a request may read.
 *
 * <p>This is the tenancy boundary. When one application serves several
 * pharmacies, a branch is a pharmacy, and the whole promise of the arrangement
 * is that one cannot see another's takings, stock or suppliers. That promise is
 * kept here or it is not kept at all.
 *
 * <p><b>The branch comes from the session, not from the request.</b> Sixty-one
 * endpoints accepted {@code branchId} as a query parameter and passed it
 * straight to the query, so changing a number in the URL read another
 * pharmacy's books. The logic below was already written once, correctly, inside
 * InventoryController; it is here so every endpoint gets the same answer instead
 * of each deciding for itself.
 *
 * <p><b>Three rules, and the reasons they are what they are.</b>
 *
 * <ul>
 *   <li><b>No branch asked for means your own branch</b> — never "all". A page
 *       that quietly widens to every pharmacy because a parameter was forgotten
 *       is the failure this class exists to prevent.</li>
 *   <li><b>An administrator may look elsewhere.</b> Someone who owns several
 *       pharmacies needs a consolidated view, so ROLE01 may name another branch
 *       or ask for all of them.</li>
 *   <li><b>Anyone else who names another branch gets 404, not 403.</b> A 403
 *       confirms the branch exists, which tells a competitor's staff member
 *       something they should not learn by guessing numbers. This matches the
 *       cross-tenant rule already used in Product Master.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BranchScopeService {

    /** The role permitted to look across pharmacies. */
    private static final String ADMIN = "ROLE01";

    private final AllBranchRepository branchRepo;

    /**
     * The organisation that runs the platform, as opposed to the ones that buy it.
     *
     * <p>Configurable because it is a deployment fact, not a law: a second
     * installation would have a different one, and hard-coding 1 would make this
     * class quietly wrong there.
     */
    @org.springframework.beans.factory.annotation.Value("${app.platform.org-id:1}")
    private long platformOrgId;

    /**
     * The branch the platform is run from — the one whose staff support every
     * other pharmacy.
     *
     * <p>Configurable for the same reason as the organisation above, and with
     * the same default. It is a narrower thing than the organisation: the
     * platform's organisation may own several branches, and only one of them is
     * where the platform is administered from.
     */
    @org.springframework.beans.factory.annotation.Value("${app.platform.branch-id:1}")
    private long platformBranchId;

    /**
     * The branch to filter on.
     *
     * @param requested   the branch named in the request, or null for "mine"
     * @param allBranches true to widen to every branch — administrators only
     * @return the branch id, or null meaning every branch
     */
    public Long resolve(Long requested, boolean allBranches) {
        if (allBranches) {
            if (!isAdmin()) {
                // Silently narrowing would show a manager their own figures under
                // a heading that says "all branches", which is worse than a refusal.
                throw new BusinessException(
                        "Only an administrator can view every branch at once. You are seeing "
                        + "your own branch.",
                        HttpStatus.FORBIDDEN, "BRANCH_SCOPE_DENIED");
            }
            return null;
        }
        return resolve(requested);
    }

    /** As {@link #resolve(Long, boolean)}, for endpoints with no all-branches option. */
    public Long resolve(Long requested) {
        // A platform operator supporting a customer is, for this request, inside
        // that pharmacy. Resolved here rather than in each controller because
        // support means seeing what the customer sees — every screen, not the
        // handful somebody remembered to thread a parameter through.
        Long acting = ActingBranch.get();
        if (acting != null) return acting;

        Long session = SecurityUtils.getCurrentBranchId();

        if (requested == null || requested.equals(session)) {
            if (session == null) {
                throw new BusinessException(
                        "Your user account has no branch assigned, so there is no data to show. "
                        + "Ask an administrator to set your branch in RBAC › Users.",
                        HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
            }
            return session;
        }

        if (isAdmin()) return requireInOrg(requested);

        throw new ResourceNotFoundException("Branch", requested);
    }

    /** True when the signed-in user may read across branches. */
    public boolean isAdmin() {
        return SecurityUtils.hasRole(ADMIN);
    }

    /**
     * The operator of the platform, as distinct from an administrator of a pharmacy.
     *
     * <p>Both hold {@code ROLE01}. Only one of them is in the platform's own
     * organisation, and that is the half of the test a customer cannot pass however
     * their own roles are configured.
     */
    public boolean isPlatformOperator() {
        if (!isAdmin()) return false;
        Long org = SecurityUtils.getRealOrgId();
        return org != null && org == platformOrgId;
    }

    /**
     * The organisation id this installation treats as the platform's own.
     *
     * <p>Exposed so a refusal can say what it was measured against. "Not a platform
     * operator" on its own sends whoever reads the log to the database to find out
     * which half of the test failed, and the answer is usually that
     * {@code app.platform.org-id} is unset and defaulting to 1 on an installation
     * whose platform organisation is not 1.
     */
    public long platformOrgId() { return platformOrgId; }

    /** The branch the platform is run from. */
    public long platformBranchId() { return platformBranchId; }

    /**
     * True when this is the platform's own branch.
     *
     * <p>Null is false, not "unknown": a user with no branch is not at the
     * platform's branch, and treating an absent value as a pass is how a check
     * like this stops checking anything.
     */
    public boolean isPlatformBranch(Long branchId) {
        return branchId != null && branchId == platformBranchId;
    }

    /** The branch, checked to exist and to belong to the caller's organisation. */
    public Long requireInOrg(Long branchId) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        AllBranch b = branchRepo.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
        // Checked rather than trusted: an organisation is a harder wall than a
        // branch, and crossing it must not be possible even for an administrator
        // of a different organisation.
        // The exception the wall was never meant to keep out. Every pharmacy
        // onboarded since Phase 3 has its own organisation, so without this the
        // operator of the platform cannot reach a single customer — which is not
        // isolation, it is an outage in the support desk.
        //
        // Deliberately NOT "is an administrator": a customer whose own admin was
        // ever given ROLE01 would otherwise reach every other pharmacy. Being in
        // the platform's organisation is the half of the test they cannot pass.
        if (isPlatformOperator()) return branchId;

        if (orgId != null && b.getPidOrg() != null && !orgId.equals(b.getPidOrg())) {
            throw new ResourceNotFoundException("Branch", branchId);
        }
        return branchId;
    }
}
