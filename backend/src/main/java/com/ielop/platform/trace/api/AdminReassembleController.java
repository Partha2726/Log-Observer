package com.ielop.platform.trace.api;

import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.filter.RequestIdFilter;
import com.ielop.platform.common.security.Actor;
import com.ielop.platform.trace.BulkReassembleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code POST /api/admin/reassemble} (section 8.8): queue a bulk re-assembly of a time range. ADMINISTRATOR only, audited. */
@RestController
@Tag(name = "Administration")
public class AdminReassembleController {

    private final BulkReassembleService bulk;
    private final ApiParams params;
    private final AuditPublisher audit;

    public AdminReassembleController(BulkReassembleService bulk, ApiParams params, AuditPublisher audit) {
        this.bulk = bulk;
        this.params = params;
        this.audit = audit;
    }

    public record Accepted(UUID jobId) {
    }

    @Operation(summary = "Re-assemble every flow seen in a range (ADMINISTRATOR); resumable, one job at a time")
    @PostMapping("/api/admin/reassemble")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public Accepted reassemble(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Authentication auth) {
        ApiParams.Check check = params.check();
        ApiParams.Window w = check.window(from, to);
        check.done();
        var job = bulk.request(w.from(), w.to()).orElseThrow(() -> ApiException.conflict("A bulk re-assembly is already queued or running"));
        audit.publish(new AuditEventMessage("ADMIN_REASSEMBLE", Actor.username(auth), "processing_job", job.getId().toString(),
                Map.of("from", w.from().toString(), "to", w.to().toString()), Instant.now(), RequestIdFilter.getCurrentRequestId(), null));
        return new Accepted(job.getId());
    }
}
