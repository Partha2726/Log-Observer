package com.ielop.platform.user.dto;

import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.User;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Safe, serializable view of a {@link User}. Deliberately excludes
 * {@code passwordHash} — no code path should ever build a response DTO
 * that includes it. This is the shape returned by {@code /api/auth/me}
 * and the admin user-listing endpoint.
 */
public record UserSummary(
        String id,
        String username,
        String email,
        String fullName,
        boolean enabled,
        Set<RoleName> roles,
        Instant createdAt
) {
    public static UserSummary from(User user) {
        Set<RoleName> roleNames = user.getRoles().stream()
                .map(role -> role.getName())
                .collect(Collectors.toUnmodifiableSet());
        return new UserSummary(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.isEnabled(),
                roleNames,
                user.getCreatedAt()
        );
    }
}
