package com.friendspharma.app.security.ecom;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Everything the e-commerce layer reads from configuration ({@code app.ecom.*}).
 *
 * <p>{@link #enabled} is the feature flag: off, and every {@code /api/ecom/**}
 * request answers 404 before reaching a controller, so an ERP deployment that
 * has not switched the storefront on is unaffected by its presence.
 *
 * <p>The customer JWT has its own secret. A token signed for a customer must
 * never verify as a staff token or the other way round, and two keys is the
 * only arrangement in which that cannot happen by accident.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ecom")
public class EcomProperties {

    private boolean enabled = false;

    private Jwt jwt = new Jwt();
    private Auth auth = new Auth();

    @Data
    public static class Jwt {
        private String secret;
        private long accessTokenExpiryMs  = 7_200_000L;      // 2 hours
        private long refreshTokenExpiryMs = 2_592_000_000L;  // 30 days
    }

    @Data
    public static class Auth {
        /** Wrong passwords in a row before the account is locked. */
        private int maxFailedLogins = 5;
        /** How long a lockout lasts. */
        private int lockoutMinutes = 15;
        /** Shortest password accepted at registration and on change. */
        private int minPasswordLength = 8;
    }
}
