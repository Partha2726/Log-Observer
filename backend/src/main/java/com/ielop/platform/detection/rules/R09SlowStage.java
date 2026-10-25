package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R09: a stage took longer than max(p99 of the same stage's completed instances over the
 * last 7 days, configured floor). Session stages are skipped: they span every actor's
 * sign-in, hours apart, so their duration means nothing.
 */
@Component
public class R09SlowStage implements AnomalyRule {
    public String id() { return "R09"; }
    public String name() { return "Slow stage"; }
    public Severity severity() { return Severity.LOW; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var t = in.trace();
        var rules = in.context().rules();
        List<Finding> out = new ArrayList<>();
        for (var s : t.stages()) {
            Long d = s.durationMs();
            var def = in.definition().stage(s.name());
            if (d == null || def.isEmpty() || def.get().isSessionStage()) {
                continue;
            }
            var baseline = in.context().stageDurationP99(t.flowId(), s.name(),
                    s.startedAt().minus(Duration.ofDays(rules.getSlowStageBaselineDays())), s.startedAt());
            if (baseline.isEmpty()) {
                // No baseline yet (too few completed instances): "slow" has no meaning.
                continue;
            }
            long p99 = baseline.get();
            long threshold = Math.max(p99, rules.getSlowStageFloorMs());
            if (d <= threshold) {
                continue;
            }
            List<String> ev = TraceRules.evidence(TraceRules.firstLine(t, s.name()).stream().toList(),
                    TraceRules.lastLine(t, s.name()).stream().toList());
            out.add(new Finding(id(), severity(), "R09:" + t.flowRef() + ":" + s.name(), t.traceId(), t.flowRef(), null, null,
                    s.startedAt(), s.endedAt(), Map.of("stage", s.name(), "durationMs", d, "thresholdMs", threshold, "p99Ms", p99), ev));
        }
        return out;
    }
}
