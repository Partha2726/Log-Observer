package com.ielop.platform.support;

import com.ielop.platform.user.Role;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.User;
import com.ielop.platform.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Test-only helper for seeding roles/users directly through the
 * repositories. The test profile applies only {@code db/migration} (roles
 * exist, the dev-only seed users do not), so each test seeds exactly the
 * users it needs.
 */
public final class TestUsers {

    private TestUsers() {
    }

    public static final String RAW_PASSWORD = "CorrectHorseBattery9!";

    public static Role ensureRole(RoleRepository roleRepository, RoleName name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(new Role(name, name.name() + " (test)")));
    }

    public static User createUser(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            String username,
            RoleName roleName,
            boolean enabled
    ) {
        Role role = ensureRole(roleRepository, roleName);
        User user = new User(
                username,
                username + "@example.com",
                passwordEncoder.encode(RAW_PASSWORD),
                "Test " + username
        );
        user.addRole(role);
        User saved = userRepository.save(user);
        if (!enabled) {
            disable(userRepository, saved);
        }
        return saved;
    }

    private static void disable(UserRepository userRepository, User user) {
        try {
            var field = User.class.getDeclaredField("enabled");
            field.setAccessible(true);
            field.set(user, false);
            userRepository.save(user);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not disable test user via reflection", e);
        }
    }
}
