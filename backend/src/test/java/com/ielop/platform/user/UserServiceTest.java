package com.ielop.platform.user;

import com.ielop.platform.user.dto.CreateUserRequest;
import com.ielop.platform.user.dto.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, roleRepository, passwordEncoder);
    }

    @Test
    void createUserHashesThePasswordRatherThanStoringItRaw() {
        CreateUserRequest request = new CreateUserRequest(
                "new.user", "new.user@example.com", "PlainTextPassword1!", "New User", RoleName.BACKEND_DEVOPS_ENGINEER);
        Role devopsRole = new Role(RoleName.BACKEND_DEVOPS_ENGINEER, "Backend DevOps Engineer");

        when(userRepository.existsByUsername("new.user")).thenReturn(false);
        when(userRepository.existsByEmail("new.user@example.com")).thenReturn(false);
        when(roleRepository.findByName(RoleName.BACKEND_DEVOPS_ENGINEER)).thenReturn(Optional.of(devopsRole));
        when(passwordEncoder.encode("PlainTextPassword1!")).thenReturn("$2b$10$hashed-value-not-plaintext");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            try {
                var idField = User.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(u, java.util.UUID.randomUUID());
            } catch (Exception ignored) {}
            return u;
        });

        UserSummary result = userService.createUser(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User savedUser = captor.getValue();

        assertThat(savedUser.getPasswordHash()).isEqualTo("$2b$10$hashed-value-not-plaintext");
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("PlainTextPassword1!");
        assertThat(result.username()).isEqualTo("new.user");
        // UserSummary has no field capable of exposing the hash at all.
        assertThat(UserSummary.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("password", "passwordHash");
    }

    @Test
    void rejectsDuplicateUsername() {
        CreateUserRequest request = new CreateUserRequest(
                "taken", "unique@example.com", "Password1!", "Someone", RoleName.BACKEND_DEVOPS_ENGINEER);
        when(userRepository.existsByUsername("taken")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username");
    }

    @Test
    void rejectsDuplicateEmail() {
        CreateUserRequest request = new CreateUserRequest(
                "unique.user", "taken@example.com", "Password1!", "Someone", RoleName.BACKEND_DEVOPS_ENGINEER);
        when(userRepository.existsByUsername("unique.user")).thenReturn(false);
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email");
    }
}
