package com.ielop.platform.assistant;

import java.util.stream.Collectors;

/**
 * Renders a {@link TraceContext} for a model (section 11.4): the trace and anomalies inside a
 * {@code <context>} block, each evidence line inside its own {@code <evidence id="evN">} tag. Angle
 * brackets inside evidence text are escaped so a log line cannot close the tag.
 */
public final class PromptText {

    private PromptText() {
    }

    public static String context(TraceContext c) {
        StringBuilder sb = new StringBuilder("<context>\n");
        TraceContext.Trace t = c.trace();
        sb.append("flowId: ").append(t.flowId()).append("\nflowRef: ").append(t.flowRef())
                .append("\noverallStatus: ").append(t.overallStatus()).append('\n');
        if (t.roles() != null && !t.roles().isEmpty()) {
            sb.append("roles: ").append(t.roles().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "))).append('\n');
        }
        sb.append("stages:\n");
        for (TraceContext.Stage s : t.stages()) {
            sb.append("- ").append(s.stage()).append(": ").append(s.status());
            if (s.user() != null) {
                sb.append(", user ").append(s.user());
            }
            if (s.start() != null) {
                sb.append(", ").append(s.start()).append(" to ").append(s.end());
            }
            if (s.durationMs() != null) {
                sb.append(", ").append(s.durationMs()).append(" ms");
            }
            sb.append('\n');
        }
        if (t.firstFailure() != null) {
            TraceContext.FirstFailure f = t.firstFailure();
            sb.append("firstFailure: stage ").append(f.stage()).append(", service ").append(f.service())
                    .append(", component ").append(f.component()).append(", exception ").append(f.exception()).append('\n');
        }
        sb.append("anomalies:\n");
        if (c.anomalies().isEmpty()) {
            sb.append("- none\n");
        }
        for (TraceContext.Anomaly a : c.anomalies()) {
            sb.append("- ").append(a.ruleId()).append(" ").append(a.name()).append(" (").append(a.severity()).append(") ")
                    .append(a.details()).append('\n');
        }
        if (c.truncated() != null && (c.truncated().evidenceOmitted() > 0 || c.truncated().textsCut() > 0)) {
            sb.append("truncated: ").append(c.truncated().evidenceOmitted()).append(" evidence lines omitted, ")
                    .append(c.truncated().textsCut()).append(" texts cut\n");
        }
        sb.append("</context>\n");
        for (TraceContext.Evidence e : c.evidence()) {
            sb.append("<evidence id=\"").append(e.id()).append("\" stage=\"").append(e.stage()).append("\" service=\"").append(e.service())
                    .append("\" ts=\"").append(e.ts()).append("\" level=\"").append(e.level()).append("\" class=\"").append(e.evidenceClass())
                    .append("\" logger=\"").append(e.logger()).append("\"").append(e.httpStatus() != null ? " httpStatus=\"" + e.httpStatus() + "\"" : "")
                    .append(">").append(escape(e.text())).append("</evidence>\n");
        }
        return sb.toString();
    }

    static String escape(String text) {
        return text == null ? "" : text.replace("<", "&lt;").replace(">", "&gt;");
    }
}
