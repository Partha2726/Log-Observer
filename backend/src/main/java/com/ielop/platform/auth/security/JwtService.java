package com.ielop.platform.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Issues and validates JWT access tokens.
 *
 * <p><strong>Claims (kept deliberately minimal, 
 * instructions):</strong></p>
 * <ul>
 *   <li>{@code sub} — the user's UUID (stable identifier; usernames are
 *       mutable in principle, so the subject is the id, not the
 *       username).</li>
 *   <li>{@code username} — for convenience/logging without a DB round
 *       trip; never trusted as an authorization decision on its own.</li>
 *   <li>{@code roles} — list of role names (e.g. {@code ["ADMINISTRATOR"]}),
 *       the basis for server-side authorization.</li>
 *   <li>{@code iat}, {@code exp}, {@code iss} — standard issued-at,
 *       expiry, and issuer claims.</li>
 * </ul>
 *
 * <p>No password, email, or other PII is placed in the token — it is not
 * encrypted, only signed, so its payload must be treated as visible to
 * whoever holds it.</p>
 */
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;
    private final String issuer;

    public JwtService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration-ms}") long expirationMs,
            @Value("${security.jwt.issuer}") String issuer
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.secret is not configured. Set the JWT_SECRET environment " +
                            "variable (see backend/.env.example) — it must not be hard-coded and must " +
                            "not be left blank in any environment.");
        }
        // HS256 requires a key of at least 256 bits (32 bytes). Fail fast
        // with a clear message rather than at first token issuance.
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "security.jwt.secret is too short for HS256 (needs >= 32 bytes / 256 bits). " +
                            "Provide a longer JWT_SECRET.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
        this.issuer = issuer;
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }

    public String generateToken(AppUserPrincipal principal) {
        Instant now = Instant.now();
        List<String> roles = principal.getAuthorities().stream()
                .map(Object::toString)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toList());

        return Jwts.builder()
                .subject(principal.getId().toString())
                .claim("username", principal.getUsername())
                .claim("roles", roles)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and fully validates (signature + expiry) the given token.
     *
     * @throws JwtException if the token is malformed, tampered with,
     *                       expired, or otherwise invalid.
     */
    public Claims parseAndValidate(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @SuppressWarnings("unchecked")
    public Set<String> extractRoles(Claims claims) {
        Object rolesClaim = claims.get("roles");
        if (rolesClaim instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    public String extractUsername(Claims claims) {
        Object username = claims.get("username");
        return username != null ? username.toString() : null;
    }
}
