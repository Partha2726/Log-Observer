package com.ielop.platform.user;

import com.ielop.platform.user.dto.CreateUserRequest;
import com.ielop.platform.user.dto.UserSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Minimum administrative user-management API needed for provisioning and
 * testing. Not a full
 * user-management feature (no update/delete/deactivate here) — that is
 * future scope beyond provisioning.
 *
 * <p>Every endpoint here requires the {@code ADMINISTRATOR} role,
 * enforced server-side via {@code @PreAuthorize} (SR-02: "UI hiding
 * alone shall not constitute authorization").</p>
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<UserSummary>> listUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @PostMapping
    public ResponseEntity<UserSummary> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserSummary created = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
