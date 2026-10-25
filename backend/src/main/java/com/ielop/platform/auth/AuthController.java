package com.ielop.platform.auth;

import com.ielop.platform.auth.dto.LoginRequest;
import com.ielop.platform.auth.dto.LoginResponse;
import com.ielop.platform.user.dto.UserSummary;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (spec section 8.2) and the
 * Implementation Planning document: {@code POST /api/auth/login},
 * {@code GET /api/auth/me}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserSummary> me(Authentication authentication) {
        // authentication.getName() is the JWT subject (user id) — see
        // JwtAuthenticationFilter. Requires authentication by virtue of
        // SecurityConfig's "anyRequest().authenticated()" default.
        return ResponseEntity.ok(authService.currentUser(authentication.getName()));
    }
}
