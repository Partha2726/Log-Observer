package com.ielop.platform.detection;

import com.ielop.platform.common.PageResponse;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.security.Actor;
import com.ielop.platform.trace.api.ApiParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Anomaly endpoints (section 8.8): list with filters, detail with evidence, and review (10.2). */
@RestController
@Tag(name = "Anomalies")
@RequestMapping("/api/anomalies")
public class AnomalyController {

    static final Pattern RULE_ID = Pattern.compile("^R\\d{2}$");
    static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private final AnomalyService anomalies;
    private final ApiParams params;

    public AnomalyController(AnomalyService anomalies, ApiParams params) {
        this.anomalies = anomalies;
        this.params = params;
    }

    @Operation(summary = "Anomalies, newest window first; the range matches anomalies whose window overlaps it")
    @GetMapping
    public PageResponse<AnomalyDtos.Item> list(@RequestParam(required = false) String ruleId,
                                               @RequestParam(required = false) String severity,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String userId,
                                               @RequestParam(required = false) String flowRef,
                                               @RequestParam(required = false) String clientIp,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        ApiParams.Check check = params.check();
        if (ruleId != null && !ruleId.isBlank() && !RULE_ID.matcher(ruleId).matches()) {
            check.reject("ruleId", "must look like R01");
        }
        if (clientIp != null && !clientIp.isBlank() && !IP.matcher(clientIp).matches()) {
            check.reject("clientIp", "must be an IPv4 or IPv6 address");
        }
        String sev = check.oneOf("severity", severity, SEVERITIES);
        String st = check.oneOf("status", status, List.copyOf(new java.util.TreeSet<>(AnomalyService.STATUSES)));
        String user = check.userId("userId", userId, false);
        String ref = check.flowRef("flowRef", flowRef, false);
        ApiParams.Window window = check.optionalWindow(from, to);
        int p = check.page(page);
        int s = check.size(size);
        check.done();
        return anomalies.list(new AnomalyService.Filter(blank(ruleId), sev, st, user, ref, blank(clientIp), window.from(), window.to()), p, s);
    }

    @Operation(summary = "One anomaly with its evidence document IDs and review history")
    @GetMapping("/{id}")
    public AnomalyDtos.Detail get(@PathVariable long id) {
        return anomalies.get(id);
    }

    @Operation(summary = "Review an anomaly: NEW to CONFIRMED or DISMISSED; an administrator may reopen (to NEW). Needs the current version")
    @PatchMapping("/{id}")
    public AnomalyDtos.Detail review(@PathVariable long id, @RequestBody AnomalyDtos.ReviewRequest body, Authentication auth) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (body == null || body.status() == null || !AnomalyService.STATUSES.contains(body.status())) {
            errors.put("status", "must be one of CONFIRMED, DISMISSED or NEW");
        }
        if (body == null || body.note() == null || body.note().isBlank() || body.note().length() > 1000) {
            errors.put("note", "is required, 1 to 1000 characters");
        }
        if (body == null || body.version() == null) {
            errors.put("version", "is required");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMINISTRATOR".equals(a.getAuthority()));
        return anomalies.review(id, body.status(), body.note().strip(), body.version(), Actor.username(auth), admin);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
