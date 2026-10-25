package com.ielop.platform.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal application health endpoint for the Foundation layer.
 *
 * <p>This is intentionally simple: it confirms the application is up and
 * able to handle HTTP requests. It does not expose environment details,
 * secrets, or dependency internals. Spring Boot Actuator is also present
 * (see {@code application.yml}) with a minimal, non-sensitive exposure
 * for later operational use.</p>
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}
