package com.ielop.platform.auth;

import com.ielop.platform.auth.dto.LoginRequest;
import com.ielop.platform.auth.dto.LoginResponse;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.filter.RequestIdFilter;
import com.ielop.platform.common.ratelimit.RateLimitProperties;
import com.ielop.platform.common.ratelimit.RateLimiter;
import com.ielop.platform.user.User;
import com.ielop.platform.user.UserRepository;
import com.ielop.platform.user.dto.UserSummary;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;

import java.util.UUID;

@Service
public class AuthService {

    /** Audit actions of the tool's own sign-in (section 8.8); not the observed users' sign-ins. */
    public static final String TOOL_LOGIN_SUCCESS = "TOOL_LOGIN_SUCCESS";
    public static final String TOOL_LOGIN_FAILURE = "TOOL_LOGIN_FAILURE";

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuditPublisher audit;
    private final RateLimiter limiter;
    private final RateLimitProperties limits;

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService, UserRepository userRepository,
                       AuditPublisher audit, RateLimiter limiter, RateLimitProperties limits) {
        this.limiter = limiter;
        this.limits = limits;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.audit = audit;
    }

    /**
     * Authenticates the given credentials and, if valid, issues a JWT.
     *
     * @throws BadCredentialsException if the username does not exist or
     *                                  the password does not match. The
     *                                  same generic exception/message is
     *                                  used for both cases so a caller
     *                                  cannot distinguish "no such user"
     *                                  from "wrong password" (SR-04/SR-06).
     * @throws DisabledException       if the account exists but is
     *                                  disabled.
     */
    public LoginResponse login(LoginRequest request) {
        // Section 8.10: at most loginPerMinute attempts per client IP.
        limiter.acquire("login:" + clientIp(), limits.loginPerMinute(), java.time.Duration.ofMinutes(1), "sign-in attempts");
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (AuthenticationException e) {
            // The attempted username is recorded as typed (cut to 64 characters); never the password.
            audit(TOOL_LOGIN_FAILURE, request.username(), Map.of("reason", e.getClass().getSimpleName()));
            throw e;
        }
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        audit(TOOL_LOGIN_SUCCESS, principal.getUsername(), Map.of());
        String token = jwtService.generateToken(principal);

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user vanished during login"));

        return LoginResponse.of(token, jwtService.getExpirationSeconds(), UserSummary.from(user));
    }

    private static String clientIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs.getRequest().getRemoteAddr() : null;
    }

    private void audit(String action, String username, Map<String, Object> details) {
        String actor = username == null || username.isBlank() ? "unknown" : username.length() > 64 ? username.substring(0, 64) : username;
        String ip = clientIp();
        audit.publish(new AuditEventMessage(action, actor, "user", actor, details, Instant.now(), RequestIdFilter.getCurrentRequestId(), ip));
    }

    /**
     * Loads the safe profile for the currently authenticated user, whose
     * id is the JWT subject (see {@code JwtAuthenticationFilter}).
     */
    public UserSummary currentUser(String userId) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired authentication token."));
        return UserSummary.from(user);
    }
}
