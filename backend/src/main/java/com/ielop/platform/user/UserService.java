package com.ielop.platform.user;

import com.ielop.platform.user.dto.CreateUserRequest;
import com.ielop.platform.user.dto.UserSummary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> listUsers() {
        return userRepository.findAll().stream().map(UserSummary::from).toList();
    }

    /**
     * Administrative user provisioning (Administrator only — enforced by
     * {@code UserController}). Not self-registration: FR-01 says the
     * system "shall allow users permitted by the access model to
     * register or be provisioned" — provisioning by an Administrator is
     * the simplest defensible reading for this stage, since there is no
     * public registration flow.
     */
    @Transactional
    public UserSummary createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username is already taken.");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email is already registered.");
        }

        Role role = roleRepository.findByName(request.role())
                .orElseThrow(() -> new IllegalStateException(
                        "Role " + request.role() + " is not seeded — check V2__identity_and_security.sql."));

        User user = new User(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName()
        );
        user.addRole(role);

        return UserSummary.from(userRepository.save(user));
    }
}
