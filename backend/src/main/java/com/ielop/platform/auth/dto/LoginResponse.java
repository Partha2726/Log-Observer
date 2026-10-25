package com.ielop.platform.auth.dto;

import com.ielop.platform.user.dto.UserSummary;

/**
 * Response for a successful {@code POST /api/auth/login}. Never includes
 * a password or password hash — see {@link UserSummary}.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserSummary user
) {
    public static LoginResponse of(String accessToken, long expiresInSeconds, UserSummary user) {
        return new LoginResponse(accessToken, "Bearer", expiresInSeconds, user);
    }
}
