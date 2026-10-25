package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.RuleInput.StageFact;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** R04: reached stages did not start in flow-definition order. */
@Component
public class R04StageOrder implements AnomalyRule {
    public String id() { return "R04"; }
    public String name() { return "Stage order violation"; }
    public Severity severity() { return Severity.HIGH; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var t = in.trace();
        List<StageFact> reached = t.stages().stream().filter(s -> s.reached() && s.startedAt() != null).toList();
        List<String> outOfOrder = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        for (int i = 0; i < reached.size(); i++) {
            for (int j = i + 1; j < reached.size(); j++) {
                if (reached.get(j).startedAt().isBefore(reached.get(i).startedAt())) {
                    String late = reached.get(j).name();
                    if (!outOfOrder.contains(late)) {
                        outOfOrder.add(late);
                        TraceRules.firstLine(t, late).ifPresent(ev::add);
                        TraceRules.firstLine(t, reached.get(i).name()).ifPresent(ev::add);
                    }
                }
            }
        }
        if (outOfOrder.isEmpty() || ev.isEmpty()) {
            return List.of();
        }
        return List.of(new Finding(id(), severity(), "R04:" + t.flowRef(), t.traceId(), t.flowRef(), null, null,
                t.firstSeen(), t.lastSeen(), Map.of("stagesStartedEarly", outOfOrder), TraceRules.evidence(ev)));
    }
}
