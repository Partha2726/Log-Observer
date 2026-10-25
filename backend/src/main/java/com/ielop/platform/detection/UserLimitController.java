package com.ielop.platform.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.filter.RequestIdFilter;
import com.ielop.platform.common.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/admin/user-limits/import} (section 8.8, feeds R10): replaces every
 * {@code user_limit} row with the approval limits of a {@code users.jsonl} file.
 * Users without a limit (no approve permission) are skipped. ADMINISTRATOR only, audited.
 */
@RestController
@Tag(name = "Administration")
public class UserLimitController {

    private final UserLimitRepository limits;
    private final ObjectMapper json;
    private final AuditPublisher audit;

    public UserLimitController(UserLimitRepository limits, ObjectMapper json, AuditPublisher audit) {
        this.limits = limits;
        this.json = json;
        this.audit = audit;
    }

    public record ImportResult(int imported, int skipped) {
    }

    @Operation(summary = "Replace the approval limits from a users.jsonl file (ADMINISTRATOR)")
    @PostMapping(path = "/api/admin/user-limits/import", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public ImportResult importLimits(@RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        List<UserLimitEntity> rows = new ArrayList<>();
        int skipped = 0;
        int lineNo = 0;
        Instant now = Instant.now();
        for (String line : new String(file.getBytes(), StandardCharsets.UTF_8).split("\n")) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode n;
            try {
                n = json.readTree(line);
            } catch (IOException e) {
                throw ApiException.validation(Map.of("file", "line " + lineNo + " is not valid JSON"));
            }
            if (!n.hasNonNull("userId") || !n.has("limitAmount")) {
                throw ApiException.validation(Map.of("file", "line " + lineNo + " needs userId and limitAmount"));
            }
            if (n.get("limitAmount").isNull()) {
                skipped++;
                continue;
            }
            List<String> roles = new ArrayList<>();
            n.path("roles").forEach(r -> roles.add(r.asText()));
            rows.add(new UserLimitEntity(n.get("userId").asText(), roles.toArray(String[]::new),
                    new BigDecimal(n.get("limitAmount").asText()), n.path("currency").asText("USD"), now));
        }
        limits.deleteAllInBatch();
        limits.saveAll(rows);
        audit.publish(new AuditEventMessage("USER_LIMITS_IMPORTED", Actor.username(auth), "user_limit", null,
                Map.of("imported", rows.size(), "skipped", skipped), now, RequestIdFilter.getCurrentRequestId(), null));
        return new ImportResult(rows.size(), skipped);
    }
}
