package com.ielop.platform.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.filter.RequestIdFilter;
import com.ielop.platform.common.job.ProcessingJob;
import com.ielop.platform.common.job.ProcessingJobManager;
import com.ielop.platform.common.ratelimit.RateLimitProperties;
import com.ielop.platform.common.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace summaries (sections 11.4, 11.5 and 11.9). A request reuses a COMPLETED summary with the same
 * context hash, prompt version and model (unless regenerating), returns the running job for the same
 * trace, hash and prompt version, or queues a new {@code AI_SUMMARY} job. The worker validates the
 * model's answer, retries once with the validation errors, and stores COMPLETED or FAILED; rows are
 * never changed after a terminal state.
 */
@Service
public class SummaryService {

    public static final String JOB_TYPE = "AI_SUMMARY";
    static final int RAW_LIMIT = 4_000;
    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    /** The row as the API shows it. */
    public record Summary(long summaryId, UUID jobId, String flowRef, String status, JsonNode output, String provider, String model,
                          String promptVersion, String contextHash, Instant createdAt, Instant completedAt, String errorCode,
                          Integer inputTokens, Integer outputTokens, Map<String, String> evidence) {
    }

    /** {@code POST} outcome: a cached summary (200) or a queued or running job (202). */
    public record Requested(Summary cached, UUID jobId, long summaryId) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TraceContextBuilder contexts;
    private final AiCalls ai;
    private final AiBudget budget;
    private final AiAvailability availability;
    private final OutputValidator validator;
    private final Prompts prompts;
    private final Redactor redactor;
    private final ProcessingJobManager jobs;
    private final AuditPublisher audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final RateLimiter limiter;
    private final RateLimitProperties limits;
    private final Clock clock = Clock.systemUTC();

    public SummaryService(NamedParameterJdbcTemplate jdbc, TraceContextBuilder contexts, AiCalls ai, AiBudget budget,
                          AiAvailability availability, OutputValidator validator, Prompts prompts, Redactor redactor,
                          ProcessingJobManager jobs, AuditPublisher audit, ObjectMapper json, TransactionTemplate tx,
                          RateLimiter limiter, RateLimitProperties limits) {
        this.limiter = limiter;
        this.limits = limits;
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.ai = ai;
        this.budget = budget;
        this.availability = availability;
        this.validator = validator;
        this.prompts = prompts;
        this.redactor = redactor;
        this.jobs = jobs;
        this.audit = audit;
        this.json = json;
        this.tx = tx;
    }

    public Requested request(String flowRef, boolean regenerate, String user) {
        if (!availability.enabled()) {
            throw AiException.disabled(availability.reason());
        }
        limiter.acquire("summary:" + user, limits.summaryRequestsPerHour(), java.time.Duration.ofHours(1), "AI summary requests");
        budget.check(user);
        TraceContextBuilder.Built built = contexts.build(flowRef);
        return tx.execute(status -> {
            // One request at a time per trace: concurrent POSTs see each other's rows.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('ai_summary:' || :t))", new MapSqlParameterSource("t", built.traceId()), Object.class);
            if (!regenerate) {
                Optional<Summary> cached = latest("""
                        WHERE s.trace_id = :t AND s.context_hash = :h AND s.prompt_version = :p AND s.model IN (:models) AND s.status = 'COMPLETED'""",
                        params(built).addValue("models", ai.gateway().models()));
                if (cached.isPresent()) {
                    return new Requested(cached.get(), null, cached.get().summaryId());
                }
            }
            Optional<Summary> running = latest("""
                    WHERE s.trace_id = :t AND s.context_hash = :h AND s.prompt_version = :p AND s.status IN ('REQUESTED', 'RUNNING')""",
                    params(built));
            if (running.isPresent()) {
                return new Requested(null, running.get().jobId(), running.get().summaryId());
            }
            Instant now = clock.instant();
            long id = jdbc.queryForObject("""
                    INSERT INTO ai_summary (trace_id, status, context_hash, prompt_version, provider, model, requested_by, created_at, evidence_map)
                    VALUES (:t, 'REQUESTED', :h, :p, :provider, :m, :user, :now, CAST(:ev AS jsonb)) RETURNING id""",
                    params(built).addValue("provider", ai.gateway().provider()).addValue("m", ai.gateway().model()).addValue("user", user)
                            .addValue("now", Timestamp.from(now)).addValue("ev", write(built.evidenceDocIds())), Long.class);
            ProcessingJob job = jobs.createJob(JOB_TYPE, "summary=" + id, 3);
            jdbc.update("UPDATE ai_summary SET job_id = :j WHERE id = :id", new MapSqlParameterSource("j", job.getId()).addValue("id", id));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("flowRef", flowRef);
            meta.put("regenerate", regenerate);
            meta.put("promptVersion", Prompts.SUMMARY_VERSION);
            meta.put("model", ai.gateway().model());
            audit.publish(new AuditEventMessage("AI_SUMMARY_REQUESTED", user, "ai_summary", String.valueOf(id), meta, now,
                    RequestIdFilter.getCurrentRequestId(), null));
            return new Requested(null, job.getId(), id);
        });
    }

    @Transactional(readOnly = true)
    public Optional<Summary> latestFor(long traceId) {
        return latest("WHERE s.trace_id = :t", new MapSqlParameterSource("t", traceId));
    }

    /** Claims and runs one queued summary job; returns its ID. */
    public Optional<UUID> runNext(String workerId) {
        Optional<ProcessingJob> claimed = jobs.claimNextJob(JOB_TYPE, workerId, java.time.Duration.ofMinutes(5));
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ProcessingJob job = claimed.get();
        long id = Long.parseLong(job.getPayloadReference().substring("summary=".length()));
        try {
            String failure = run(id);
            if (failure == null) {
                jobs.completeJob(job.getId(), workerId);
            } else {
                jobs.failJob(job.getId(), workerId, failure, "Summary " + id + " failed: " + failure, false);
            }
        } catch (RuntimeException e) {
            // Not the provider's fault (for example Elasticsearch unavailable): retry the job while attempts remain.
            boolean last = job.getAttemptCount() >= job.getMaxAttempts();
            String code = e instanceof ApiException a ? a.code() : "INTERNAL_ERROR";
            log.warn("Summary {} attempt {} failed ({})", id, job.getAttemptCount(), code);
            if (last) {
                finish(id, "FAILED", null, code, null, 0, 0, null, null);
            } else {
                jdbc.update("UPDATE ai_summary SET status = 'REQUESTED' WHERE id = :id AND status = 'RUNNING'", new MapSqlParameterSource("id", id));
            }
            jobs.failJob(job.getId(), workerId, code, String.valueOf(e.getMessage()), true);
        }
        return Optional.of(job.getId());
    }

    /** Returns null when COMPLETED, else the error code it FAILED with. */
    String run(long id) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT s.status, s.requested_by, t.flow_ref FROM ai_summary s JOIN trace t ON t.id = s.trace_id WHERE s.id = :id""",
                new MapSqlParameterSource("id", id));
        String status = (String) row.get("status");
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            return "COMPLETED".equals(status) ? null : "ALREADY_FAILED";
        }
        String user = (String) row.get("requested_by");
        jdbc.update("UPDATE ai_summary SET status = 'RUNNING' WHERE id = :id", new MapSqlParameterSource("id", id));
        TraceContextBuilder.Built built = contexts.build((String) row.get("flow_ref"));
        jdbc.update("UPDATE ai_summary SET context_hash = :h, evidence_map = CAST(:ev AS jsonb) WHERE id = :id",
                new MapSqlParameterSource("h", built.hash()).addValue("ev", write(built.evidenceDocIds())).addValue("id", id));

        List<String> errors = new ArrayList<>();
        int input = 0;
        int output = 0;
        String raw = null;
        String provider = null;
        String model = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            AiGateway.SummaryResult r;
            try {
                r = ai.summarize(user, new AiGateway.SummaryRequest(built.context(), prompts.summary(), prompts.summarySchema(), List.copyOf(errors)));
            } catch (ApiException e) {
                finish(id, "FAILED", null, e.code(), null, input, output, provider, model);
                return e.code();
            }
            provider = r.provider();
            model = r.model();
            input += r.usage().inputTokens();
            output += r.usage().outputTokens();
            raw = r.rawText();
            OutputValidator.Result v = validator.validate(raw, built.context());
            if (v.valid()) {
                finish(id, "COMPLETED", v.output(), null, null, input, output, provider, model);
                return null;
            }
            errors = new ArrayList<>(v.errors());
        }
        String sanitized = raw == null ? null : redactor.redact(raw);
        if (sanitized != null && sanitized.length() > RAW_LIMIT) {
            sanitized = sanitized.substring(0, RAW_LIMIT);
        }
        finish(id, "FAILED", null, AiException.AI_OUTPUT_INVALID, sanitized, input, output, provider, model);
        return AiException.AI_OUTPUT_INVALID;
    }

    /** {@code provider}/{@code model}: who answered last (a fallback may have); null keeps the requested ones. */
    private void finish(long id, String status, JsonNode output, String errorCode, String raw, int input, int outputTokens,
                        String provider, String model) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            int changed = jdbc.update("""
                    UPDATE ai_summary SET status = :st, output = CAST(:out AS jsonb), error_code = :err, raw_output = :raw,
                           input_tokens = :in, output_tokens = :outTokens, completed_at = :now,
                           provider = COALESCE(CAST(:prov AS varchar), provider), model = COALESCE(CAST(:model AS varchar), model)
                     WHERE id = :id AND status IN ('REQUESTED', 'RUNNING')""",
                    new MapSqlParameterSource("st", status).addValue("out", output == null ? null : output.toString()).addValue("err", errorCode)
                            .addValue("raw", raw).addValue("in", input).addValue("outTokens", outputTokens).addValue("now", Timestamp.from(now))
                            .addValue("id", id).addValue("prov", provider).addValue("model", model));
            if (changed == 0) {
                return;
            }
            String user = jdbc.queryForObject("SELECT requested_by FROM ai_summary WHERE id = :id", new MapSqlParameterSource("id", id), String.class);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("status", status);
            meta.put("provider", provider != null ? provider : ai.gateway().provider());
            meta.put("model", model != null ? model : ai.gateway().model());
            meta.put("inputTokens", input);
            meta.put("outputTokens", outputTokens);
            if (errorCode != null) {
                meta.put("errorCode", errorCode);
            }
            audit.publish(new AuditEventMessage("COMPLETED".equals(status) ? "AI_SUMMARY_COMPLETED" : "AI_SUMMARY_FAILED", user, "ai_summary",
                    String.valueOf(id), meta, now, null, null));
        });
    }

    private Optional<Summary> latest(String where, MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT s.*, t.flow_ref, s.output::text AS output_text, s.evidence_map::text AS evidence_text
                  FROM ai_summary s JOIN trace t ON t.id = s.trace_id""" + " " + where + " ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
                p, this::summary).stream().findFirst();
    }

    private Summary summary(ResultSet rs, int i) throws SQLException {
        Timestamp done = rs.getTimestamp("completed_at");
        return new Summary(rs.getLong("id"), rs.getObject("job_id", UUID.class), rs.getString("flow_ref"), rs.getString("status"),
                readTree(rs.getString("output_text")), rs.getString("provider"), rs.getString("model"), rs.getString("prompt_version"),
                rs.getString("context_hash"), rs.getTimestamp("created_at").toInstant(), done == null ? null : done.toInstant(),
                rs.getString("error_code"), (Integer) rs.getObject("input_tokens"), (Integer) rs.getObject("output_tokens"),
                readMap(rs.getString("evidence_text")));
    }

    private MapSqlParameterSource params(TraceContextBuilder.Built built) {
        return new MapSqlParameterSource("t", built.traceId()).addValue("h", built.hash()).addValue("p", Prompts.SUMMARY_VERSION);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readTree(String s) {
        try {
            return s == null ? null : json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> readMap(String s) {
        try {
            return s == null ? Map.of() : json.readValue(s, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
