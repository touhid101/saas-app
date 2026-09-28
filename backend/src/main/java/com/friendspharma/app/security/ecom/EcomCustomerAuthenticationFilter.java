package com.friendspharma.app.security.ecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Puts the customer on the request for {@code /api/ecom/**}.
 *
 * <p>Three jobs, all confined to the e-commerce paths:
 * <ol>
 *   <li><b>Feature flag.</b> With {@code app.ecom.enabled=false} every
 *       e-commerce path answers 404 here, controllers included, so the
 *       storefront is absent rather than half-present.</li>
 *   <li><b>Customer routes ignore staff sessions.</b> Under
 *       {@code /api/ecom/} but outside {@code /api/ecom/admin/}, a staff
 *       {@link UserPrincipal} the JWT filter may have set is cleared. A
 *       pharmacist's token cannot read a customer's profile, and the answer is
 *       401 (nobody signed in) rather than 403 (somebody refused).</li>
 *   <li><b>Customer tokens.</b> A bearer token that verifies on the customer key
 *       as an ACCESS token names a live customer, who becomes the principal
 *       with {@code ROLE_ECOM_CUSTOMER}. The row is re-read on every request so
 *       a suspension or a tier change takes effect immediately, not at the
 *       next sign-in.</li>
 * </ol>
 * Runs after the staff JWT filter and before everything else, so the staff
 * filters downstream see no {@code UserPrincipal} and step aside.
 */
@Slf4j
@RequiredArgsConstructor
public class EcomCustomerAuthenticationFilter extends OncePerRequestFilter {

    public static final String PREFIX       = "/api/ecom/";
    public static final String ADMIN_PREFIX = "/api/ecom/admin/";

    private final EcomProperties props;
    private final EcomTokenProvider tokens;
    private final EcomCustomerRepository customers;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error("E-commerce is not enabled on this server.", "ECOM_DISABLED"));
            return;
        }

        String path = request.getRequestURI();
        boolean customerRoute = !path.startsWith(ADMIN_PREFIX);

        if (customerRoute) {
            Authentication existing = SecurityContextHolder.getContext().getAuthentication();
            if (existing != null && existing.getPrincipal() instanceof UserPrincipal) {
                SecurityContextHolder.clearContext();
            }
            String token = bearer(request);
            if (StringUtils.hasText(token)) {
                Optional<Claims> claims = tokens.parse(token, EcomTokenProvider.TYPE_ACCESS);
                claims.map(EcomTokenProvider::customerIdOf)
                      .flatMap(customers::findById)
                      .filter(c -> "Y".equals(c.getStatus()))
                      .ifPresent(c -> authenticate(c, request));
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(EcomCustomer c, HttpServletRequest request) {
        EcomCustomerPrincipal principal = new EcomCustomerPrincipal(
                c.getPidEcomCustomer(), c.getMobileNo(), c.getCustomerType(), c.getApprovalStatus());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return StringUtils.hasText(header) && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
