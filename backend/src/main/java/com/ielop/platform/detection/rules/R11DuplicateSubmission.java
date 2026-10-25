package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleContext.Submission;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** R11: the same requester, counterparty hash and amount were submitted again within 60 s. */
@Component
public class R11DuplicateSubmission implements AnomalyRule {
    public String id() { return "R11"; }
    public String name() { return "Duplicate submission"; }
    public Severity severity() { return Severity.MEDIUM; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var p = in.positions();
        var t = in.trace();
        String requester = t.actors().get(p.role(0));
        if (requester == null || t.counterpartyHash() == null || t.amount() == null) {
            return List.of();
        }
        Duration window = Duration.ofSeconds(in.context().rules().getDuplicateWindowSeconds());
        List<String> mine = TraceRules.successLines(t, p.creationStage());
        if (mine.isEmpty() || p.creationStage().isEmpty()) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (Submission other : in.context().sameSubmissions(t.flowId(), requester, t.counterpartyHash(), t.amount(),
                t.firstSeen().minus(window), t.firstSeen().plus(window), t.traceId(),
                p.creationStage().get().name(), p.creationStage().get().successEvents())) {
            boolean otherFirst = other.firstSeen().isBefore(t.firstSeen())
                    || (other.firstSeen().equals(t.firstSeen()) && other.flowRef().compareTo(t.flowRef()) < 0);
            String earlier = otherFirst ? other.flowRef() : t.flowRef();
            String later = otherFirst ? t.flowRef() : other.flowRef();
            // One row per pair, always on the later submission's trace, whichever trace was assembled last.
            long laterTrace = otherFirst ? t.traceId() : other.traceId();
            out.add(new Finding(id(), severity(), "R11:" + earlier + ":" + later, laterTrace, later, requester, null,
                    otherFirst ? other.firstSeen() : t.firstSeen(), otherFirst ? t.firstSeen() : other.firstSeen(),
                    Map.of("earlierFlowRef", earlier, "laterFlowRef", later, "amount", t.amount()),
                    TraceRules.evidence(other.creationDocIds(), mine)));
        }
        return out;
    }
}
