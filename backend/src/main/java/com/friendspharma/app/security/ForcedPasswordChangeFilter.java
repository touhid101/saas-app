package com.friendspharma.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.friendspharma.app.repository.AclUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * [V71] Makes "must change password" mean it.
 *
 * <p>An account holding a temporary password may do exactly two things: read who
 * it is, and set a password of its own. Everything else is refused until it has.
 *
 * <p>This exists because the alternative does not work. Returning a flag at
 * login and having the screen redirect is a request the client is free to
 * ignore, and anything holding the access token — a script, a saved session, an
 * old build of the app — ignores it by default. The temporary password was
 * handed over out of band, possibly read down a phone, and it is the weakest
 * credential in the system; leaving it able to trade is the whole risk the
 * forced change was meant to remove.
 *
 * <p>Expiry is enforced here too. A temporary password past its window is
 * refused outright rather than merely flagged, so a password left unused for a
 * week cannot be used at all.
 */
@Slf4j
@RequiredArgsConstructor
public class ForcedPasswordChangeFilter extends OncePerRequestFilter {

    private final AclUserRepository users;
    private final ObjectMapper objectMapper;

    /**
     * What an account mid-reset may still reach.
     *
     * <p>Deliberately tiny. {@code /api/auth/me} so the app can render who is
     * signed in, {@code /api/auth/me/password} so they can complete the change,
     * and logout so they can leave. Refresh is allowed so a token expiring
     * mid-change does not strand them on the one screen they are permitted.
     */
    private static final Set<String> ALLOWED = Set.of(
            "/api/auth/me",
            "/api/auth/me/password",
            "/api/auth/logout",
            "/api/auth/refresh");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        /* Login itself must never be filtered: the flag is read from the account
           the request is trying to authenticate as, and there is no
           authenticated user yet. */
        return path == null
            || path.startsWith("/api/auth/login")
            || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) { chain.doFilter(request, response); return; }

        String path = request.getServletPath();
        if (ALLOWED.contains(path)) { chain.doFilter(request, response); return; }

        var user = users.findById(userId).orElse(null);
        if (user == null || !user.mustChangePassword()) { chain.doFilter(request, response); return; }

        LocalDateTime expires = user.getTempPasswordExpires();
        boolean expired = expires != null && expires.isBefore(LocalDateTime.now());

        log.info("Refused {} for {} — the account is holding a {} temporary password.",
                path, user.getUserName(), expired ? "an expired" : "a");

        /* 403 rather than 401. The credentials were valid — the account simply
           may not act yet — and 401 would send well-behaved clients into a
           refresh-and-retry loop against a state that refreshing cannot clear. */
        write(response, expired
                ? "The temporary password issued for this account has expired. Ask the "
                  + "administrator to issue a new one."
                : "This account is using a temporary password. Set a password of your own "
                  + "before continuing.",
              expired ? "TEMP_PASSWORD_EXPIRED" : "PASSWORD_CHANGE_REQUIRED");
    }

    private void write(HttpServletResponse response, String message, String code) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("errorCode", code);
        body.put("timestamp", LocalDateTime.now().toString());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
