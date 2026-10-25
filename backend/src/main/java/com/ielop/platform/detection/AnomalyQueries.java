package com.ielop.platform.detection;

import com.ielop.platform.trace.TraceAnomalies;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The anomalies of one trace, for the trace API and graph badges. */
@Component
public class AnomalyQueries implements TraceAnomalies {

    private final AnomalyRepository anomalies;
    private final RuleEngine engine;

    public AnomalyQueries(AnomalyRepository anomalies, RuleEngine engine) {
        this.anomalies = anomalies;
        this.engine = engine;
    }

    @Override
    @Transactional(readOnly = true)
    public List<View> forTrace(long traceId) {
        Map<String, String> names = engine.ruleNames();
        return anomalies.findByTraceIdOrderByIdAsc(traceId).stream()
                .map(a -> new View(a.getId(), a.getRuleId(), names.getOrDefault(a.getRuleId(), a.getRuleId()), a.getSeverity(),
                        a.getStatus(), a.getDetails(), List.of(a.getEvidenceDocIds()), a.getVersion()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<View> forUser(String userId, Instant from, Instant to) {
        Map<String, String> names = engine.ruleNames();
        return anomalies.findForUser(userId, from, to, PageRequest.of(0, 100)).stream()
                .map(a -> new View(a.getId(), a.getRuleId(), names.getOrDefault(a.getRuleId(), a.getRuleId()), a.getSeverity(),
                        a.getStatus(), a.getDetails(), List.of(a.getEvidenceDocIds()), a.getVersion()))
                .toList();
    }
}
