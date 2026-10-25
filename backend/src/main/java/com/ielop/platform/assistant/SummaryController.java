package com.ielop.platform.assistant;

import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.security.Actor;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.trace.api.ApiParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** AI overview of a trace (section 8.8). */
@RestController
@Tag(name = "AI")
@RequestMapping("/api/traces/{flowRef}/ai-summary")
public class SummaryController {

    private final SummaryService summaries;
    private final TraceService traces;
    private final ApiParams params;

    public SummaryController(SummaryService summaries, TraceService traces, ApiParams params) {
        this.summaries = summaries;
        this.traces = traces;
        this.params = params;
    }

    public record RequestBodyDto(Boolean regenerate) {
    }

    public record Queued(UUID jobId, long summaryId) {
    }

    @Operation(summary = "Request an AI overview: 200 with a cached summary, or 202 with the job that produces it")
    @PostMapping
    public ResponseEntity<?> request(@PathVariable String flowRef, @RequestBody(required = false) RequestBodyDto body, Authentication auth) {
        ApiParams.Check check = params.check();
        check.flowRef("flowRef", flowRef, true);
        check.done();
        SummaryService.Requested r = summaries.request(flowRef, body != null && Boolean.TRUE.equals(body.regenerate()), Actor.username(auth));
        if (r.cached() != null) {
            return ResponseEntity.ok(r.cached());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Queued(r.jobId(), r.summaryId()));
    }

    @Operation(summary = "The latest summary of the trace and its status; evidence maps evN to log document IDs")
    @GetMapping
    public SummaryService.Summary latest(@PathVariable String flowRef) {
        ApiParams.Check check = params.check();
        check.flowRef("flowRef", flowRef, true);
        check.done();
        long traceId = traces.findStored(flowRef).orElseThrow(() -> ApiException.notFound("No assembled trace " + flowRef)).trace().getId();
        return summaries.latestFor(traceId).orElseThrow(() -> ApiException.notFound("No AI summary was requested for " + flowRef));
    }
}
