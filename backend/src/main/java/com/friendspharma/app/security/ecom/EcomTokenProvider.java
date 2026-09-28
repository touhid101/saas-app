package com.friendspharma.app.security.ecom;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and reads customer tokens — the customer counterpart of
 * {@link com.friendspharma.app.security.JwtTokenProvider}, on its own key.
 *
 * <p>Every token carries {@code aud = CUSTOMER} and {@code typ = ACCESS|REFRESH}.
 * A staff token presented on a customer route fails signature verification
 * here (different key); a customer refresh token presented as an access token
 * fails on {@code typ}. Both are refused silently — the request simply has no
 * customer on it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EcomTokenProvider {

    public static final String AUDIENCE = "CUSTOMER";
    public static final String TYPE_ACCESS  = "ACCESS";
    public static final String TYPE_REFRESH = "REFRESH";

    private final EcomProperties props;

    private SecretKey key() {
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "app.ecom.jwt.secret must be set (ECOM_JWT_SECRET) and be at least 32 characters.");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String accessToken(EcomCustomerPrincipal p) {
        return build(p, TYPE_ACCESS, props.getJwt().getAccessTokenExpiryMs());
    }

    public String refreshToken(EcomCustomerPrincipal p) {
        return build(p, TYPE_REFRESH, props.getJwt().getRefreshTokenExpiryMs());
    }

    private String build(EcomCustomerPrincipal p, String type, long expiryMs) {
        return Jwts.builder()
                .subject(p.loginIdentifier())
                .audience().add(AUDIENCE).and()
                .claim("cid", p.customerId())
                .claim("typ", type)
                .claim("tier", p.customerType())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(key())
                .compact();
    }

    /**
     * The claims of a valid customer token of the given type, or empty.
     * Empty for: bad signature, expired, wrong audience, wrong type, malformed.
     */
    public Optional<Claims> parse(String token, String expectedType) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Claims c = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
            boolean audienceOk = c.getAudience() != null && c.getAudience().contains(AUDIENCE);
            if (!audienceOk || !expectedType.equals(c.get("typ", String.class))) return Optional.empty();
            return Optional.of(c);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Customer token rejected: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static Long customerIdOf(Claims c) {
        Object v = c.get("cid");
        return v instanceof Number n ? n.longValue() : null;
    }

    public long accessExpiryMs()  { return props.getJwt().getAccessTokenExpiryMs(); }
    public long refreshExpiryMs() { return props.getJwt().getRefreshTokenExpiryMs(); }
}
