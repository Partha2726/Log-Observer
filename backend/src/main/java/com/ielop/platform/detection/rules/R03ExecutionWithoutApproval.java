package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** R03: the final stage ran although the approval stage is NOT_REACHED, FAILED or REJECTED. */
@Component
public class R03ExecutionWithoutApproval implements AnomalyRule {
    public String id() { return "R03"; }
    public String name() { return "Execution without approval"; }
    public Severity severity() { return Severity.CRITICAL; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var p = in.positions();
        var t = in.trace();
        var approval = p.approvalStage();
        String fin = p.finalStage().name();
        if (approval.isEmpty() || approval.get().name().equals(fin)) {
            return List.of();
        }
        var finalStage = t.stage(fin);
        var approvalStage = t.stage(approval.get().name());
        if (finalStage.isEmpty() || !finalStage.get().reached() || approvalStage.isEmpty()
                || !Set.of("NOT_REACHED", "FAILED", "REJECTED").contains(approvalStage.get().status())) {
            return List.of();
        }
        List<String> approvalProblem = t.linesOf(approval.get().name()).stream()
                .filter(l -> l.isError() || (l.eventAction() != null && approval.get().rejectEvents().contains(l.eventAction())))
                .map(l -> l.docId()).limit(1).toList();
        List<String> ev = TraceRules.evidence(TraceRules.firstLine(t, fin).stream().toList(), approvalProblem);
        return List.of(new Finding(id(), severity(), "R03:" + t.flowRef(), t.traceId(), t.flowRef(),
                t.actors().get(p.role(-1)), null, t.firstSeen(), t.lastSeen(),
                Map.of("approvalStage", approval.get().name(), "approvalStatus", approvalStage.get().status(), "finalStage", fin), ev));
    }
}
