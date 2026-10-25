package com.ielop.platform.detection;

import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.filter.RequestIdFilter;
import com.ielop.platform.common.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /api/admin/rule-scan} (section 8.8): queues a {@code RULE_SCAN} over
 * {@code [from, to]} (default: the last {@code scanLookbackHours}). 409 while another
 * scan is queued or running. ADMINISTRATOR only, audited.
 */
@RestController
@Tag(name = "Administration")
public class RuleScanController {

    static final Duration MAX_RANGE = Duration.ofDays(31);

    private final RuleScanService scans;
    private final RulesProperties rules;
    private final AuditPublisher audit;

    public RuleScanController(RuleScanService scans, RulesProperties rules, AuditPublisher audit) {
        this.scans = scans;
        this.rules = rules;
        this.audit = audit;
    }

    public record Accepted(UUID jobId) {
    }

    @Operation(summary = "Queue a rule scan over a time range (ADMINISTRATOR)")
    @PostMapping("/api/admin/rule-scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public Accepted scan(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Authentication auth) {
        Map<String, String> errors = new LinkedHashMap<>();
        Instant now = Instant.now();
        Instant t = parse("to", to, now, errors);
        Instant f = parse("from", from, t == null ? null : t.minus(Duration.ofHours(rules.getScanLookbackHours())), errors);
        if (errors.isEmpty() && !f.isBefore(t)) {
            errors.put("from", "must be before to");
        } else if (errors.isEmpty() && Duration.between(f, t).compareTo(MAX_RANGE) > 0) {
            errors.put("from", "the range may span at most 31 days");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        var job = scans.request(f, t).orElseThrow(() -> ApiException.conflict("A rule scan is already queued or running"));
        audit.publish(new AuditEventMessage("RULE_SCAN_REQUESTED", Actor.username(auth), "processing_job", job.getId().toString(),
                Map.of("from", f.toString(), "to", t.toString()), now, RequestIdFilter.getCurrentRequestId(), null));
        return new Accepted(job.getId());
    }

    private static Instant parse(String field, String value, Instant fallback, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            errors.put(field, "must be an ISO-8601 instant such as 2026-05-15T00:00:00Z");
            return null;
        }
    }
}
