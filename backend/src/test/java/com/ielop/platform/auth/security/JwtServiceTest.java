package com.ielop.platform.auth.security;

import com.ielop.platform.user.Role;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for JWT generation/validation — no Spring context needed.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-signing-secret-32-bytes-minimum-000000";
    private static final String OTHER_SECRET = "a-completely-different-signing-secret-also-32-bytes";
    private static final long EXPIRATION_MS = 60_000;
    private static final String ISSUER = "ielop-platform-unit-test";

    private AppUserPrincipal buildPrincipal(RoleName roleName) throws Exception {
        User user = new User("jane.doe", "jane.doe@example.com", "irrelevant-hash", "Jane Doe");
        setId(user, UUID.randomUUID());
        Role role = new Role(roleName, "test role");
        user.addRole(role);
        return new AppUserPrincipal(user);
    }

    private void setId(Object entity, UUID id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    @Test
    void generatesTokenThatRoundTripsWithSameServiceInstance() throws Exception {
        JwtService jwtService = new JwtService(SECRET, EXPIRATION_MS, ISSUER);
        AppUserPrincipal principal = buildPrincipal(RoleName.ADMINISTRATOR);

        String token = jwtService.generateToken(principal);
        var claims = jwtService.parseAndValidate(token);

        assertThat(claims.getSubject()).isEqualTo(principal.getId().toString());
        assertThat(jwtService.extractUsername(claims)).isEqualTo("jane.doe");
        assertThat(jwtService.extractRoles(claims)).containsExactly("ADMINISTRATOR");
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() throws Exception {
        JwtService issuer = new JwtService(OTHER_SECRET, EXPIRATION_MS, ISSUER);
        JwtService verifier = new JwtService(SECRET, EXPIRATION_MS, ISSUER);
        AppUserPrincipal principal = buildPrincipal(RoleName.BACKEND_DEVOPS_ENGINEER);

        String token = issuer.generateToken(principal);

        assertThatThrownBy(() -> verifier.parseAndValidate(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void rejectsTamperedToken() throws Exception {
        JwtService jwtService = new JwtService(SECRET, EXPIRATION_MS, ISSUER);
        AppUserPrincipal principal = buildPrincipal(RoleName.BACKEND_DEVOPS_ENGINEER);
        String token = jwtService.generateToken(principal);

        // Flip a character in the payload segment to simulate tampering.
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[payload.length / 2] = payload[payload.length / 2] == 'a' ? 'b' : 'a';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parseAndValidate(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        // A JwtService whose configured lifetime is already negative
        // issues tokens that expire immediately/in the past.
        JwtService shortLived = new JwtService(SECRET, -1_000, ISSUER);
        JwtService verifier = new JwtService(SECRET, EXPIRATION_MS, ISSUER);
        AppUserPrincipal principal = buildPrincipal(RoleName.BACKEND_DEVOPS_ENGINEER);

        String token = shortLived.generateToken(principal);

        assertThatThrownBy(() -> verifier.parseAndValidate(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsGarbageToken() {
        JwtService jwtService = new JwtService(SECRET, EXPIRATION_MS, ISSUER);
        assertThatThrownBy(() -> jwtService.parseAndValidate("not-a-jwt-at-all"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtService("too-short", EXPIRATION_MS, ISSUER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtService("   ", EXPIRATION_MS, ISSUER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encodesOnlyMinimalClaims() throws Exception {
        JwtService jwtService = new JwtService(SECRET, EXPIRATION_MS, ISSUER);
        AppUserPrincipal principal = buildPrincipal(RoleName.BACKEND_DEVOPS_ENGINEER);
        String token = jwtService.generateToken(principal);
        var claims = jwtService.parseAndValidate(token);

        assertThat(claims.keySet()).containsExactlyInAnyOrder(
                "sub", "username", "roles", "iss", "iat", "exp");
        // The password hash (or any other PII beyond username) must never
        // end up in the token payload.
        assertThat(claims.values()).noneMatch(v -> v != null && v.toString().contains("irrelevant-hash"));
    }
}
