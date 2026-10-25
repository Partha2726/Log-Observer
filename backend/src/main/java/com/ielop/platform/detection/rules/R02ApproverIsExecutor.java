package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** R02: the last two roles (approver, executor) are the same user. Disabled by default (company policy). */
@Component
public class R02ApproverIsExecutor implements AnomalyRule {
    public String id() { return "R02"; }
    public String name() { return "Approver is executor"; }
    public Severity severity() { return Severity.MEDIUM; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var p = in.positions();
        var t = in.trace();
        if (in.definition().roles().size() < 2) {
            return List.of();
        }
        String approver = t.actors().get(p.role(-2));
        String executor = t.actors().get(p.role(-1));
        if (approver == null || !approver.equals(executor)) {
            return List.of();
        }
        List<String> ev = TraceRules.evidence(TraceRules.successLines(t, p.stageOfRole(p.role(-2))),
                TraceRules.firstLine(t, p.finalStage().name()).stream().toList());
        if (ev.isEmpty()) {
            return List.of();
        }
        return List.of(new Finding(id(), severity(), "R02:" + t.flowRef(), t.traceId(), t.flowRef(), approver, null,
                t.firstSeen(), t.lastSeen(), Map.of("user", approver), ev));
    }
}
