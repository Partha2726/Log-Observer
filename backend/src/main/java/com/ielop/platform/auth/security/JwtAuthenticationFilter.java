package com.ielop.platform.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.common.exception.ApiError;
import com.ielop.platform.common.security.Actor;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a {@code Bearer} JWT on every request, and if valid, populates
 * the {@link org.springframework.security.core.context.SecurityContext}
 * for the remainder of the request.
 *
 * <p>Deliberately stateless: authorities are taken directly from the
 * token's {@code roles} claim rather than re-querying the database on
 * every request. {@code /api/auth/me} is the place that does a full,
 * fresh DB lookup when up-to-date profile data is needed.</p>
 *
 * <p>Requests with no {@code Authorization} header, or a header that
 * isn't a well-formed Bearer token, are simply passed through
 * unauthenticated — {@link SecurityConfig} + the entry point handle
 * turning "unauthenticated" into a 401 for endpoints that require it.
 * Only a <em>present but invalid/expired/tampered</em> token is rejected
 * here, with a clear 401 response, so a bad token can never silently
 * fall through as "no token".</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parseAndValidate(token);
            String userId = claims.getSubject();
            Set<String> roles = jwtService.extractRoles(claims);
            Set<GrantedAuthority> authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .collect(Collectors.toUnmodifiableSet());

            var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authentication.setDetails(new Actor(userId, jwtService.extractUsername(claims), request.getRemoteAddr()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected request with invalid JWT: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, request.getRequestURI());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                "Invalid or expired authentication token.",
                path
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
