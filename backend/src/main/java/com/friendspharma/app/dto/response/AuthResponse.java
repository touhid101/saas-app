package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AuthResponse {

    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final UserResponse user;
    private final LocalDateTime expiresAt;

    /**
     * [V71] True when this account is holding a password somebody else chose.
     *
     * <p>The UI reads it to send the user straight to a change-password screen.
     * It is not the enforcement — ForcedPasswordChangeFilter is, because a flag
     * the client is asked to honour is honoured only by clients that choose to.
     */
    private final boolean mustChangePassword;
}
