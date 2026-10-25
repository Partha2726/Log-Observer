package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** R10: the flow's amount is above the approver's limit (user_limit) and the approval stage completed. */
@Component
public class R10ApprovalAboveLimit implements AnomalyRule {
    public String id() { return "R10"; }
    public String name() { return "Approval above limit"; }
    public Severity severity() { return Severity.HIGH; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var p = in.positions();
        var t = in.trace();
        var approval = p.approvalStage();
        if (approval.isEmpty() || t.amount() == null) {
            return List.of();
        }
        String approver = t.actors().get(p.role(1));
        if (approver == null || t.stage(approval.get().name()).map(s -> !"COMPLETED".equals(s.status())).orElse(true)) {
            return List.of();
        }
        var limit = in.context().userLimit(approver);
        if (limit.isEmpty() || t.amount().compareTo(limit.get()) <= 0) {
            return List.of();
        }
        List<String> ev = TraceRules.evidence(TraceRules.successLines(t, p.creationStage()), TraceRules.successLines(t, approval));
        if (ev.isEmpty()) {
            return List.of();
        }
        return List.of(new Finding(id(), severity(), "R10:" + t.flowRef(), t.traceId(), t.flowRef(), approver, null,
                t.firstSeen(), t.lastSeen(), Map.of("amount", t.amount(), "limit", limit.get(), "currency", String.valueOf(t.currency())), ev));
    }
}
