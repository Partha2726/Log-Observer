package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.RuleInput.LineFact;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** R05: a stage FAILED; HIGH for the approval and final stages, MEDIUM otherwise. One finding per stage. */
@Component
public class R05StageFailed implements AnomalyRule {
    public String id() { return "R05"; }
    public String name() { return "Stage failed"; }
    public Severity severity() { return Severity.HIGH; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var p = in.positions();
        var t = in.trace();
        String approval = p.approvalStage().map(s -> s.name()).orElse(null);
        String fin = p.finalStage().name();
        List<Finding> out = new ArrayList<>();
        for (var s : t.stages()) {
            if (!"FAILED".equals(s.status())) {
                continue;
            }
            List<LineFact> lines = t.linesOf(s.name());
            String cause = lines.stream().anyMatch(l -> l.docId().equals(t.firstFailureDocId())) ? t.firstFailureDocId()
                    : lines.stream().filter(l -> l.isError() && l.observed()).map(LineFact::docId).findFirst()
                    .orElse(lines.stream().filter(l -> l.httpStatus() != null && l.httpStatus() >= 400).map(LineFact::docId)
                            .reduce((a, b) -> b).orElse(null));
            if (cause == null) {
                continue;
            }
            Severity sev = s.name().equals(approval) || s.name().equals(fin) ? Severity.HIGH : Severity.MEDIUM;
            out.add(new Finding(id(), sev, "R05:" + t.flowRef() + ":" + s.name(), t.traceId(), t.flowRef(),
                    s.name().equals(approval) ? t.actors().get(p.role(1)) : null, null, s.startedAt(), s.endedAt(),
                    Map.of("stage", s.name()), List.of(cause)));
        }
        return out;
    }
}
