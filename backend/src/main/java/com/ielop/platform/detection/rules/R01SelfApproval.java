package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** R01: the first role (requester) is also the second (approver); segregation of duties. */
@Component
public class R01SelfApproval implements AnomalyRule {
    public String id() { return "R01"; }
    public String name() { return "Self-approval"; }
    public Severity severity() { return Severity.CRITICAL; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var p = in.positions();
        var t = in.trace();
        var approval = p.approvalStage();
        if (approval.isEmpty()) {
            return List.of();
        }
        String first = t.actors().get(p.role(0));
        String second = t.actors().get(p.role(1));
        if (first == null || !first.equals(second) || t.stage(approval.get().name()).map(s -> !"COMPLETED".equals(s.status())).orElse(true)) {
            return List.of();
        }
        List<String> ev = TraceRules.evidence(TraceRules.successLines(t, p.creationStage()), TraceRules.successLines(t, approval));
        if (ev.isEmpty()) {
            return List.of();
        }
        return List.of(new Finding(id(), severity(), "R01:" + t.flowRef(), t.traceId(), t.flowRef(), first, null,
                t.firstSeen(), t.lastSeen(), Map.of("user", first, "roles", List.of(p.role(0), p.role(1))), ev));
    }
}
