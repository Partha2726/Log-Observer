package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.RuleInput.LineFact;
import com.ielop.platform.detection.RuleInput.TraceFacts;
import com.ielop.platform.flowdef.StageDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Evidence helpers shared by the trace-scoped rules. */
final class TraceRules {

    private TraceRules() {
    }

    /** The stage's success-event lines (for example the creation or approval line). */
    static List<String> successLines(TraceFacts t, Optional<StageDefinition> stage) {
        if (stage.isEmpty()) {
            return List.of();
        }
        Set<String> success = stage.get().successEvents();
        return t.linesOf(stage.get().name()).stream()
                .filter(l -> l.eventAction() != null && success.contains(l.eventAction()))
                .map(LineFact::docId).toList();
    }

    static Optional<String> firstLine(TraceFacts t, String stage) {
        return t.linesOf(stage).stream().map(LineFact::docId).findFirst();
    }

    static Optional<String> lastLine(TraceFacts t, String stage) {
        List<LineFact> lines = t.linesOf(stage);
        return lines.isEmpty() ? Optional.empty() : Optional.of(lines.get(lines.size() - 1).docId());
    }

    @SafeVarargs
    static List<String> evidence(List<String>... parts) {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> p : parts) {
            out.addAll(p);
        }
        return new ArrayList<>(out);
    }
}
