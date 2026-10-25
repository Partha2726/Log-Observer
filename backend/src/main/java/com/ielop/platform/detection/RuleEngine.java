package com.ielop.platform.detection;

import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.trace.StoredTrace;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the enabled rules (section 10): trace-scoped rules on one stored trace, scan
 * rules on a time window, and stores every finding through {@link AnomalyStore}.
 */
@Service
public class RuleEngine {

    private final List<AnomalyRule> rules;
    private final RuleContext context;
    private final AnomalyStore store;
    private final TraceService traces;
    private final FlowDefinitionRegistry flows;
    private final MeterRegistry meters;
    private final Clock clock = Clock.systemUTC();

    public RuleEngine(List<AnomalyRule> rules, RuleContext context, AnomalyStore store, TraceService traces,
                      FlowDefinitionRegistry flows, MeterRegistry meters) {
        this.rules = rules.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
        this.context = context;
        this.store = store;
        this.traces = traces;
        this.flows = flows;
        this.meters = meters;
    }

    public List<AnomalyRule> rules() {
        return rules;
    }

    public Map<String, String> ruleNames() {
        Map<String, String> out = new LinkedHashMap<>();
        rules.forEach(r -> out.put(r.id(), r.name()));
        return out;
    }

    /** Trace-scoped rules on the stored trace of {@code flowRef}; returns the findings. */
    @Transactional
    public List<Finding> evaluateTrace(String flowRef) {
        StoredTrace stored = traces.findStored(flowRef).orElse(null);
        if (stored == null) {
            return List.of();
        }
        var def = flows.byId(stored.trace().getFlowId()).orElse(null);
        if (def == null) {
            return List.of();
        }
        RuleInput in = RuleInput.forTrace(def, facts(stored), context);
        return run(AnomalyRule.Scope.TRACE, in);
    }

    /** Scan rules over {@code [from, to]}. */
    @Transactional
    public List<Finding> scan(Instant from, Instant to) {
        return run(AnomalyRule.Scope.SCAN, RuleInput.forWindow(from, to, context));
    }

    private List<Finding> run(AnomalyRule.Scope scope, RuleInput in) {
        List<Finding> all = new ArrayList<>();
        Instant now = clock.instant();
        for (AnomalyRule rule : rules) {
            if (rule.scope() != scope || !context.rules().enabled(rule.id())) {
                continue;
            }
            for (Finding f : rule.evaluate(in)) {
                if (store.upsert(f, now)) {
                    meters.counter("rules.findings", "ruleId", f.ruleId()).increment();
                }
                all.add(f);
            }
        }
        return all;
    }

    static RuleInput.TraceFacts facts(StoredTrace s) {
        var t = s.trace();
        Map<String, String> actors = new LinkedHashMap<>();
        s.actors().forEach(a -> actors.put(a.getRole(), a.getUserId()));
        List<RuleInput.StageFact> stages = s.stages().stream()
                .map(st -> new RuleInput.StageFact(st.getStage(), st.getStageOrder(), st.getStatus(), st.getStartedAt(), st.getEndedAt()))
                .toList();
        List<RuleInput.LineFact> lines = s.links().stream().map(RuleEngine::line).toList();
        return new RuleInput.TraceFacts(t.getId(), t.getFlowRef(), t.getFlowId(), t.getFirstSeen(), t.getLastSeen(), t.getAmount(),
                t.getCurrency(), t.getCounterpartyHash(), actors, stages, lines, t.getFirstFailureDocId());
    }

    private static RuleInput.LineFact line(TraceEventLinkEntity l) {
        List<String> ext = l.getExternalIds() == null ? List.of() : Arrays.asList(l.getExternalIds().split(","));
        return new RuleInput.LineFact(l.getEsDocId(), l.getStage(), l.getEventTs(), l.getService(), l.getLogger(), l.getEventAction(),
                l.getUserId(), l.getHttpStatus(), l.getErrorType(), l.getProviderStatus(), ext, l.isError(),
                "OBSERVED".equals(l.getEvidenceClass()));
    }
}
