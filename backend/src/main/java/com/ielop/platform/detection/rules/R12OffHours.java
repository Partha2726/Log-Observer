package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** R12: the approval or final stage started between offHoursStart and offHoursEnd in the business timezone. */
@Component
public class R12OffHours implements AnomalyRule {
    public String id() { return "R12"; }
    public String name() { return "Off-hours activity"; }
    public Severity severity() { return Severity.LOW; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var p = in.positions();
        var t = in.trace();
        var rules = in.context().rules();
        Set<String> stages = new LinkedHashSet<>();
        p.approvalStage().ifPresent(s -> stages.add(s.name()));
        stages.add(p.finalStage().name());
        List<Finding> out = new ArrayList<>();
        for (String name : stages) {
            var s = t.stage(name);
            if (s.isEmpty() || s.get().startedAt() == null) {
                continue;
            }
            int hour = s.get().startedAt().atZone(rules.zone()).getHour();
            if (hour < rules.getOffHoursStart() || hour >= rules.getOffHoursEnd()) {
                continue;
            }
            var first = TraceRules.firstLine(t, name);
            if (first.isEmpty()) {
                continue;
            }
            String role = in.definition().stage(name).map(d -> d.actorRole()).orElse(null);
            out.add(new Finding(id(), severity(), "R12:" + t.flowRef() + ":" + name, t.traceId(), t.flowRef(),
                    role == null ? null : t.actors().get(role), null,
                    s.get().startedAt(), s.get().endedAt(),
                    Map.of("stage", name, "localHour", hour, "timezone", rules.getBusinessTimezone()), List.of(first.get())));
        }
        return out;
    }
}
