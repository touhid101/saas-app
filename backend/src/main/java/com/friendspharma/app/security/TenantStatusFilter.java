package com.friendspharma.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.friendspharma.app.service.impl.TenantStatusService;
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
import java.util.List;
import java.util.Map;

/**
 * Turns a pharmacy away when it has stopped paying.
 *
 * <p><b>Why a filter and not a check in each service.</b> There are 264 endpoints.
 * A rule applied in 264 places is a rule that will be missed in one of them, and
 * the one that is missed is the one somebody finds. Here it applies to everything
 * by default and the exceptions are listed in one place, so a controller added
 * next year is protected because nobody remembered to protect it.
 *
 * <p><b>Deny by default.</b> {@link #ALWAYS_OPEN} is the complete list of paths
 * that work without a live subscription: signing in, signing out, reading your own
 * status, and paying. Everything else is gated. Note what is deliberately absent —
 * there is no wildcard, and no "read-only endpoints are fine" exemption, because
 * a report is exactly what an unpaid tenant would most want to export on the way
 * out.
 *
 * <p><b>Super Admin is exempt</b>, because somebody has to be able to look at and
 * reactivate an expired branch. That exemption is on the person, not the path.
 *
 * <p>Returns <b>402 Payment Required</b> rather than 403: the request is not
 * forbidden, it is unpaid, and the frontend routes on that difference.
 */
@Slf4j
@RequiredArgsConstructor
public class TenantStatusFilter extends OncePerRequestFilter {

    private final TenantStatusService tenantStatus;
    private final ObjectMapper json;

    /** The only paths that work without a live subscription. */
    private static final List<String> ALWAYS_OPEN = List.of(
            "/api/auth/",              // sign in, refresh, sign out, and your own profile
            "/api/subscription/mine",  // what you owe and until when
            "/api/subscription/pay",   // the way back in
            "/api/public/",            // sign-up: there is no tenant to check yet
            "/api/admin/",             // Super Admin console, itself role-guarded
            "/swagger-ui", "/v3/api-docs", "/actuator/health", "/actuator/info",
            "/uploads/"
    );

    public static final String ERROR_CODE = "SUBSCRIPTION_EXPIRED";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        return ALWAYS_OPEN.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Not signed in yet: authentication has its own answer for that, and it
        // is a better one than "your subscription expired".
        if (SecurityUtils.getCurrentUser() == null) {
            chain.doFilter(request, response);
            return;
        }

        // Somebody has to be able to administer a branch that cannot administer
        // itself, so this exemption is on the person rather than the path.
        if (SecurityUtils.isSuperAdmin()) {
            chain.doFilter(request, response);
            return;
        }

        TenantStatusService.Access access = tenantStatus.check(SecurityUtils.getCurrentBranchId());
        if (access.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        log.debug("Blocked {} {} — branch {} is {}", request.getMethod(), request.getRequestURI(),
                SecurityUtils.getCurrentBranchId(), access.tenantStatus());
        reject(response, access);
    }

    private void reject(HttpServletResponse response, TenantStatusService.Access access) throws IOException {
        response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);   // 402
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // Shaped like every other error the API returns, so the client needs no
        // special case to read it — only to act on the code.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", access.reason());
        body.put("errorCode", ERROR_CODE);
        body.put("tenantStatus", access.tenantStatus());
        body.put("expiresOn", access.expiresOn() == null ? null : access.expiresOn().toString());
        body.put("timestamp", LocalDateTime.now().toString());

        json.writeValue(response.getWriter(), body);
    }
}
