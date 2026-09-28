package com.friendspharma.app.security;

import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomProperties;
import com.friendspharma.app.security.ecom.EcomTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A customer token is a customer token and nothing else: it must not verify
 * on the staff key, a refresh token must not pass as an access token, and a
 * staff token must not pass here at all.
 */
class EcomTokenProviderTest {

    private static EcomTokenProvider provider(String secret) {
        EcomProperties p = new EcomProperties();
        p.getJwt().setSecret(secret);
        p.getJwt().setAccessTokenExpiryMs(60_000);
        p.getJwt().setRefreshTokenExpiryMs(120_000);
        return new EcomTokenProvider(p);
    }

    private static final EcomCustomerPrincipal CUSTOMER =
            new EcomCustomerPrincipal(42L, "8801712345678", "WHOLESALER", "APPROVED");

    @Test
    @DisplayName("an access token carries the customer id, audience and type")
    void accessTokenRoundTrip() {
        EcomTokenProvider tp = provider("customer-secret-that-is-long-enough-for-hmac-256");
        Optional<Claims> claims = tp.parse(tp.accessToken(CUSTOMER), EcomTokenProvider.TYPE_ACCESS);
        assertThat(claims).isPresent();
        assertThat(EcomTokenProvider.customerIdOf(claims.get())).isEqualTo(42L);
        assertThat(claims.get().getSubject()).isEqualTo("8801712345678");
        assertThat(claims.get().getAudience()).contains(EcomTokenProvider.AUDIENCE);
    }

    @Test
    @DisplayName("a refresh token is refused where an access token is expected, and the reverse")
    void typesDoNotCross() {
        EcomTokenProvider tp = provider("customer-secret-that-is-long-enough-for-hmac-256");
        assertThat(tp.parse(tp.refreshToken(CUSTOMER), EcomTokenProvider.TYPE_ACCESS)).isEmpty();
        assertThat(tp.parse(tp.accessToken(CUSTOMER), EcomTokenProvider.TYPE_REFRESH)).isEmpty();
    }

    @Test
    @DisplayName("a token signed with another key — a staff token, say — is refused")
    void otherKeyRefused() {
        EcomTokenProvider a = provider("customer-secret-that-is-long-enough-for-hmac-256");
        EcomTokenProvider b = provider("a-different-secret-that-is-also-long-enough-1234");
        assertThat(b.parse(a.accessToken(CUSTOMER), EcomTokenProvider.TYPE_ACCESS)).isEmpty();
        assertThat(a.parse("not.a.token", EcomTokenProvider.TYPE_ACCESS)).isEmpty();
        assertThat(a.parse(null, EcomTokenProvider.TYPE_ACCESS)).isEmpty();
    }
}
