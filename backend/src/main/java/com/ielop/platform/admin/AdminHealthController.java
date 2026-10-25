package com.ielop.platform.admin;

import com.ielop.platform.logsearch.EsUnavailableException;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /api/admin/health}: the admin health card (section 17). Component health (db,
 * elasticsearch, ai), job queues, ingestion counts and parse failures, findings and AI summaries of the
 * last 24 hours, and the alert-worthy values that currently apply. ADMINISTRATOR only.
 */
@RestController
@Tag(name = "Administration")
public class AdminHealthController {

    static final List<String> PARSE_FAILURE_TAGS = List.of("_grokparsefailure", "_jsonparsefailure");

    public record Component(String status, Map<String, Object> details) {
    }

    public record Ingest(long events, long parseFailures, double parseFailureRatio, Instant latestEventAt, Instant latestIngestedAt) {
    }

    public record AdminHealth(Instant at, Map<String, Component> components, Map<String, Map<String, Long>> jobs, Ingest ingest,
                              String ingestError, Map<String, Long> findingsLast24h, Map<String, Long> summariesLast24h, List<String> alerts) {
    }

    private final HealthEndpoint health;
    private final JdbcTemplate jdbc;
    private final LogQueryService logs;

    public AdminHealthController(HealthEndpoint health, JdbcTemplate jdbc, LogQueryService logs) {
        this.health = health;
        this.jdbc = jdbc;
        this.logs = logs;
    }

    @Operation(summary = "Admin health card: components, job queues, ingestion, findings and AI summaries (ADMINISTRATOR)")
    @GetMapping("/api/admin/health")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public AdminHealth get() {
        Instant now = Instant.now();
        Map<String, Component> components = new LinkedHashMap<>();
        for (String name : List.of("db", "elasticsearch", "ai")) {
            HealthComponent c = health.healthForPath(name);
            if (c != null) {
                components.put(name, new Component(c.getStatus().getCode(), c instanceof Health h ? h.getDetails() : Map.of()));
            }
        }
        Map<String, Map<String, Long>> jobs = new TreeMap<>();
        jdbc.query("SELECT job_type, status, count(*) FROM processing_jobs GROUP BY 1, 2", rs -> {
            jobs.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(rs.getString(2), rs.getLong(3));
        });
        Ingest ingest = null;
        String ingestError = null;
        try {
            long events = logs.count("admin.events", LogCriteria.builder().build());
            long failures = logs.count("admin.parse-failures", LogCriteria.builder().tags(PARSE_FAILURE_TAGS).build());
            Instant latestEvent = logs.findLatest("admin.latest", LogCriteria.builder().build()).map(h -> h.timestamp()).orElse(null);
            Instant latestIngest = logs.maxIngestedAt("admin.ingested", LogCriteria.builder().build()).orElse(null);
            ingest = new Ingest(events, failures, events == 0 ? 0 : (double) failures / events, latestEvent, latestIngest);
        } catch (EsUnavailableException e) {
            ingestError = "Log store unavailable: " + e.getMessage();
        }
        Map<String, Long> findings = new TreeMap<>();
        jdbc.query("SELECT rule_id, count(*) FROM anomaly WHERE detected_at >= ? GROUP BY 1",
                rs -> { findings.put(rs.getString(1), rs.getLong(2)); }, java.sql.Timestamp.from(now.minus(Duration.ofHours(24))));
        Map<String, Long> summaries = new TreeMap<>();
        jdbc.query("SELECT status, count(*) FROM ai_summary WHERE created_at >= ? GROUP BY 1",
                rs -> { summaries.put(rs.getString(1), rs.getLong(2)); }, java.sql.Timestamp.from(now.minus(Duration.ofHours(24))));

        List<String> alerts = new ArrayList<>();
        components.forEach((name, c) -> {
            if ("DOWN".equals(c.status()) || "OUT_OF_SERVICE".equals(c.status())) {
                alerts.add(name + " is " + c.status());
            }
        });
        if (ingest != null && ingest.parseFailureRatio() > 0.001) {
            alerts.add(String.format("Parse failures are %.2f%% of events (alert above 0.1%%)", ingest.parseFailureRatio() * 100));
        }
        long queued = jobs.values().stream().mapToLong(m -> m.getOrDefault("QUEUED", 0L)).sum();
        if (queued > 100) {
            alerts.add(queued + " jobs are queued (alert above 100)");
        }
        long done = summaries.getOrDefault("COMPLETED", 0L) + summaries.getOrDefault("FAILED", 0L);
        if (done > 0 && summaries.getOrDefault("FAILED", 0L) * 10 > done) {
            alerts.add("More than 10% of AI summaries failed in the last 24 hours");
        }
        return new AdminHealth(now, components, jobs, ingest, ingestError, findings, summaries, alerts);
    }
}
