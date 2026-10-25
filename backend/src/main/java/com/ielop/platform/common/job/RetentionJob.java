package com.ielop.platform.common.job;

import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nightly retention (section 9.2): DISMISSED anomalies older than a year (CONFIRMED and NEW are kept),
 * AI summaries, conversations (with their messages) and token usage older than 90 days. Trace rows are
 * derived and kept until reset; audit events are never deleted by the application. Audited as
 * {@code RETENTION_RUN} by {@code system}.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final AuditPublisher audit;
    private final Duration anomalies;
    private final Duration ai;
    private final Clock clock = Clock.systemUTC();

    public RetentionJob(JdbcTemplate jdbc, AuditPublisher audit, @Value("${app.retention.dismissed-anomaly-days:365}") long anomalyDays,
                        @Value("${app.retention.ai-days:90}") long aiDays) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.anomalies = Duration.ofDays(anomalyDays);
        this.ai = Duration.ofDays(aiDays);
    }

    @Transactional
    public Map<String, Integer> run() {
        Instant now = clock.instant();
        Timestamp anomalyCut = Timestamp.from(now.minus(anomalies));
        Timestamp aiCut = Timestamp.from(now.minus(ai));
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("anomaly", jdbc.update("DELETE FROM anomaly WHERE status = 'DISMISSED' AND detected_at < ?", anomalyCut));
        deleted.put("ai_summary", jdbc.update("DELETE FROM ai_summary WHERE created_at < ? AND status IN ('COMPLETED', 'FAILED')", aiCut));
        deleted.put("ai_message", jdbc.update("DELETE FROM ai_message WHERE created_at < ?", aiCut));
        deleted.put("ai_conversation", jdbc.update("""
                DELETE FROM ai_conversation c WHERE c.created_at < ? AND NOT EXISTS (SELECT 1 FROM ai_message m WHERE m.conversation_id = c.id)""", aiCut));
        deleted.put("ai_token_usage", jdbc.update("DELETE FROM ai_token_usage WHERE day < ?", java.time.LocalDate.ofInstant(now.minus(ai), java.time.ZoneOffset.UTC)));
        audit.publish(new AuditEventMessage("RETENTION_RUN", "system", "retention", null, new LinkedHashMap<>(deleted), now, null, null));
        log.info("Retention removed {}", deleted);
        return deleted;
    }

    /** Every night at 03:30 (server time); runs only when scheduling is enabled (common.job.SchedulingConfig). */
    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void nightly() {
        run();
    }
}
