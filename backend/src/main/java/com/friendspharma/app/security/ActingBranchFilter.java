package com.friendspharma.app.security;

import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.SubAudit;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.SubAuditRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets a platform operator act as one pharmacy, and records that they did.
 *
 * <p><b>One at a time, never all at once.</b> This is not a cross-tenant view: it
 * scopes the whole application to a single named pharmacy, so the operator sees
 * what that customer sees. Adding several pharmacies together on one screen is a
 * different feature and deliberately absent.
 *
 * <p><b>Who may.</b> Only a platform operator — {@code ROLE01} <em>in the platform's
 * own organisation</em>. The role alone is not enough: a customer whose own
 * administrator was ever given ROLE01 would otherwise reach every other pharmacy
 * on the estate. The organisation check is what makes that impossible rather than
 * merely unlikely.
 *
 * <p><b>Recorded, always.</b> Looking at somebody's trade is not a neutral act,
 * and an operator who cannot be seen doing it is one nobody can hold to account.
 * Every request that carries the header writes to the same trail as a suspension
 * or a payment.
 */
@Slf4j
@RequiredArgsConstructor
public class ActingBranchFilter extends OncePerRequestFilter {

    private final AllBranchRepository branchRepo;
    private final SubAuditRepository auditRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    /** Asked rather than re-decided: see {@link #isPlatformOperator()}. */
    private final com.friendspharma.app.service.impl.BranchScopeService branchScope;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(ActingBranch.HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Long branchId;
            try {
                branchId = Long.valueOf(header.trim());
            } catch (NumberFormatException e) {
                reject(response, 400, "BAD_ACTING_BRANCH",
                        "The pharmacy to act as must be a branch id.");
                return;
            }

            if (!isPlatformOperator()) {
                // Refused, not silently ignored. Ignoring it would show the operator
                // their own data under another pharmacy's name, which is worse than
                // saying no.
                // Says which half of the test failed and what it was measured
                // against. The bare "without being a platform operator" sent
                // whoever read it to the database to find out, and the answer is
                // usually one of the two numbers on this line.
                log.warn("{} (role {}, organisation {}) tried to act as branch {}. "
                         + "A platform operator holds ROLE01 in organisation {} "
                         + "(app.platform.org-id).",
                         SecurityUtils.getCurrentUsername(),
                         SecurityUtils.getCurrentRoleCode(),
                         SecurityUtils.getRealOrgId(),
                         branchId,
                         branchScope.platformOrgId());
                reject(response, 403, "NOT_PLATFORM_OPERATOR",
                        "Only a platform administrator can view another pharmacy.");
                return;
            }

            AllBranch branch = branchRepo.findById(branchId).orElse(null);
            if (branch == null) {
                reject(response, 404, "BRANCH_NOT_FOUND", "There is no pharmacy with id " + branchId + ".");
                return;
            }

            ActingBranch.set(branchId, branch.getPidOrg());
            recordAccess(branchId, branch.getBranchName(), request);
            chain.doFilter(request, response);

        } finally {
            // A value left behind would follow this thread into the next request,
            // which could belong to anybody at all.
            ActingBranch.clear();
        }
    }

    /**
     * A platform operator, not merely an administrator.
     *
     * <p>{@code ROLE01} in the platform's own organisation. A tenant's own
     * administrator holds a role inside their organisation and can never satisfy
     * the second half of this.
     *
     * <p><b>Asked of {@link com.friendspharma.app.service.impl.BranchScopeService},
     * not decided here.</b> This method used to test {@code isSuperAdmin()}, which
     * reads the {@code IS_ADMIN} column, while BranchScopeService — which every
     * endpoint behind this filter then consults — tests the {@code ROLE01} role.
     * Two definitions of one idea, and an account holding ROLE01 in the platform
     * organisation without the column set satisfied the second and was refused by
     * the first: the Tenants list opened, and opening a pharmacy on it answered
     * "Only a platform administrator can view another pharmacy."
     *
     * <p>They cannot drift apart again, because there is now one of them.
     */
    private boolean isPlatformOperator() {
        return branchScope.isPlatformOperator();
    }

    /**
     * Written on its own, outside whatever the request goes on to do.
     *
     * <p>A failure to record must not fail the request — but it must be loud,
     * because an unrecorded look at a customer's data is the thing this exists to
     * prevent.
     */
    private void recordAccess(Long branchId, String branchName, HttpServletRequest request) {
        try {
            auditRepo.save(SubAudit.builder()
                    .pidBranch(branchId)
                    .eventType("VIEWED_BY_PLATFORM")
                    .detail(request.getMethod() + " " + request.getRequestURI())
                    .newValue(branchName)
                    .actedBy(SecurityUtils.getCurrentUsername())
                    .actedAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException e) {
            log.error("Could not record that {} viewed branch {}. The request proceeds.",
                      SecurityUtils.getCurrentUsername(), branchId, e);
        }
    }

    private void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("errorCode", code);
        body.put("timestamp", LocalDateTime.now().toString());
        json.writeValue(response.getWriter(), body);
    }
}
