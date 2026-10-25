package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleContext.StuckTrace;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import com.ielop.platform.flowdef.FlowDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R06: the creation stage completed, but no approval came within {@code stuckAfterMinutes}
 * and the final stage was never reached. A failed or missing approval both count; a
 * rejection is a normal outcome. "Now" is the end of the scan window.
 */
@Component
public class R06StuckFlow implements AnomalyRule {
    public String id() { return "R06"; }
    public String name() { return "Stuck flow"; }
    public Severity severity() { return Severity.MEDIUM; }
    public Scope scope() { return Scope.SCAN; }

    public List<Finding> evaluate(RuleInput in) {
        var ctx = in.context();
        Instant cutoff = in.to().minus(Duration.ofMinutes(ctx.rules().getStuckAfterMinutes()));
        if (cutoff.isBefore(in.from())) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (FlowDefinition def : ctx.definitions()) {
            var p = new RuleInput.Positions(def);
            var creation = p.creationStage();
            var approval = p.approvalStage();
            String fin = p.finalStage().name();
            if (creation.isEmpty() || approval.isEmpty() || approval.get().name().equals(fin)) {
                continue;
            }
            for (StuckTrace t : ctx.stuckTraces(def, creation.get().name(), approval.get().name(), fin,
                    creation.get().successEvents(), in.from(), cutoff)) {
                out.add(new Finding(id(), severity(), "R06:" + t.flowRef(), t.traceId(), t.flowRef(), t.requester(), null,
                        t.firstSeen(), in.to(), Map.of("approvalStage", approval.get().name(), "approvalStatus", t.approvalStatus(),
                        "stuckAfterMinutes", ctx.rules().getStuckAfterMinutes()), t.creationDocIds()));
            }
        }
        return out;
    }
}
