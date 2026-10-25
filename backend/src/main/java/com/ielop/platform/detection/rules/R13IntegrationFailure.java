package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.RuleInput.LineFact;
import com.ielop.platform.detection.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * R13: in the final stage, an integration call failed: a line carrying an external
 * correlation ID (an integration line) has an error type, or the provider answered 500
 * or above.
 */
@Component
public class R13IntegrationFailure implements AnomalyRule {
    public String id() { return "R13"; }
    public String name() { return "Integration failure"; }
    public Severity severity() { return Severity.HIGH; }
    public Scope scope() { return Scope.TRACE; }

    public List<Finding> evaluate(RuleInput in) {
        var t = in.trace();
        String fin = in.positions().finalStage().name();
        List<LineFact> failing = t.linesOf(fin).stream()
                .filter(l -> !l.externalIds().isEmpty())
                .filter(l -> l.errorType() != null || status(l.providerStatus()) >= 500)
                .toList();
        if (failing.isEmpty()) {
            return List.of();
        }
        String provider = failing.stream().map(LineFact::providerStatus).filter(s -> s != null).findFirst().orElse("");
        return List.of(new Finding(id(), severity(), "R13:" + t.flowRef(), t.traceId(), t.flowRef(), null, null,
                failing.get(0).ts(), failing.get(failing.size() - 1).ts(),
                Map.of("stage", fin, "externalIds", failing.get(0).externalIds(), "providerStatus", provider,
                        "errorType", String.valueOf(failing.get(0).errorType())),
                failing.stream().map(LineFact::docId).toList()));
    }

    private static int status(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
