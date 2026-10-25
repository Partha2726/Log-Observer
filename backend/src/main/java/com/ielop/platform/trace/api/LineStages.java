package com.ielop.platform.trace.api;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.flowdef.MatchRule;
import com.ielop.platform.flowdef.StageDefinition;
import com.ielop.platform.logsearch.FieldMatch;
import com.ielop.platform.logsearch.LogHit;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage of a single log line outside a trace view, for the log search and request
 * pages: the stage it was linked to in an assembled trace, else the stage its flow
 * definition's matchers give it. Also turns a stage into Elasticsearch conditions.
 */
@Component
public class LineStages {

    private final FlowDefinitionRegistry flows;
    private final NamedParameterJdbcTemplate jdbc;

    public LineStages(FlowDefinitionRegistry flows, NamedParameterJdbcTemplate jdbc) {
        this.flows = flows;
        this.jdbc = jdbc;
    }

    public Map<String, String> stagesOf(List<LogHit> hits) {
        Map<String, String> out = new HashMap<>();
        if (hits.isEmpty()) {
            return out;
        }
        List<String> ids = hits.stream().map(LogHit::id).toList();
        jdbc.query("SELECT es_doc_id, MIN(stage) AS stage FROM trace_event_link WHERE es_doc_id IN (:ids) GROUP BY es_doc_id",
                new MapSqlParameterSource("ids", ids), rs -> {
                    out.put(rs.getString("es_doc_id"), rs.getString("stage"));
                });
        for (LogHit h : hits) {
            out.computeIfAbsent(h.id(), k -> classify(h).orElse(null));
        }
        return out;
    }

    private Optional<String> classify(LogHit h) {
        List<FlowDefinition> candidates = new ArrayList<>();
        h.flowRefs().forEach(r -> flows.forFlowRef(r).ifPresent(candidates::add));
        if (candidates.isEmpty()) {
            candidates.addAll(flows.all());
        }
        for (FlowDefinition def : candidates) {
            Optional<StageDefinition> stage = def.stageOf(h.service(), h.httpMethod(), h.urlPath(), h.eventAction());
            if (stage.isPresent()) {
                return Optional.of(stage.get().name());
            }
            if (def.isSignOut(h.service(), h.httpMethod(), h.urlPath(), h.eventAction())) {
                return Optional.of(FlowDefinition.SIGN_OUT);
            }
        }
        return Optional.empty();
    }

    /** Elasticsearch conditions for "lines of this stage" across the loaded definitions that have it. */
    public List<FieldMatch> conditions(String stage) {
        List<FieldMatch> out = new ArrayList<>();
        for (FlowDefinition def : flows.all()) {
            Collection<MatchRule> rules = FlowDefinition.SIGN_OUT.equals(stage) ? def.signOut()
                    : def.stage(stage).map(StageDefinition::match).orElse(List.of());
            for (MatchRule m : rules) {
                out.add(new FieldMatch(m.service(), m.httpMethod(), m.urlPath() == null ? null : luceneRegexp(m.urlPath().pattern()),
                        m.eventActions()));
            }
        }
        return out;
    }

    /** Java to Lucene regular expression for the simple path patterns flow definitions use (Lucene anchors implicitly). */
    static String luceneRegexp(String java) {
        String r = java;
        r = r.startsWith("^") ? r.substring(1) : ".*" + r;
        r = r.endsWith("$") && !r.endsWith("\\$") ? r.substring(0, r.length() - 1) : r + ".*";
        return r.replace("\\d", "[0-9]").replace("\\w", "[A-Za-z0-9_]");
    }

    public boolean isKnownStage(String stage) {
        return FlowDefinition.SIGN_OUT.equals(stage) || flows.all().stream().anyMatch(d -> d.stage(stage).isPresent());
    }
}
