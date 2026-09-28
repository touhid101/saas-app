package com.friendspharma.app.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        return buildToken(principal, jwtProperties.getAccessTokenExpiryMs(), "ACCESS");
    }

    public String generateAccessToken(UserPrincipal principal) {
        return buildToken(principal, jwtProperties.getAccessTokenExpiryMs(), "ACCESS");
    }

    public String generateRefreshToken(UserPrincipal principal) {
        return buildToken(principal, jwtProperties.getRefreshTokenExpiryMs(), "REFRESH");
    }

    private String buildToken(UserPrincipal principal, long expiryMs, String tokenType) {
        String roles = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        return Jwts.builder()
                .subject(principal.getUsername())
                .claim("userId", principal.getUserId())
                .claim("orgId", principal.getOrgId())
                .claim("companyId", principal.getCompanyId())
                .claim("branchId", principal.getBranchId())
                // Carried in the token so "my deliveries" needs no extra lookup per request.
                .claim("employeeId", principal.getEmployeeId())
                .claim("roleCode", principal.getRoleCode())
                .claim("roles", roles)
                .claim("type", tokenType)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims validateAndParseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            validateAndParseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public String extractUsername(String token) {
        return validateAndParseToken(token).getSubject();
    }

    public Long extractUserId(String token) {
        return validateAndParseToken(token).get("userId", Long.class);
    }
}
