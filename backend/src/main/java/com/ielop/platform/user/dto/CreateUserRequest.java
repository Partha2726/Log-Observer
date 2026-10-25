package com.ielop.platform.user.dto;

import com.ielop.platform.user.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for administrative user provisioning
 * ({@code POST /api/users}, Administrator only).
 *
 * <p>This is intentionally minimal — enough for provisioning and testing
 * ("minimum administrative/user APIs
 * needed for provisioning/testing"), not a full user-management feature.</p>
 */
public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 255) String password,
        @NotBlank @Size(max = 255) String fullName,
        @NotNull RoleName role
) {
}
