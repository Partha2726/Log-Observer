package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code AI_PROVIDER=fake} (section 11.2): deterministic, schema-valid output built from the
 * context alone, with no network. It only states what the context says, so it never invents an
 * evidence ID, a rule or a status, and it ignores instructions inside evidence text.
 */
public class FakeAiGateway implements AiGateway {

    public static final String MODEL = "fake-1";

    private final ObjectMapper json;

    public FakeAiGateway(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public SummaryResult summarize(SummaryRequest request) {
        TraceContext c = request.context();
        TraceContext.Trace t = c.trace();
        ObjectNode out = json.createObjectNode();
        TraceContext.FirstFailure ff = t.firstFailure();
        out.put("headline", cut(t.flowRef() + " " + t.overallStatus().toLowerCase()
                + (ff != null ? " at " + ff.stage() + (ff.exception() != null ? " (" + simple(ff.exception()) + ")" : "") : ""), 140));
        out.put("status", t.overallStatus());

        ArrayNode what = out.putArray("whatHappened");
        for (TraceContext.Stage s : t.stages()) {
            firstOf(c, s.stage(), false).ifPresent(ev -> {
                ObjectNode n = what.addObject();
                n.put("stage", s.stage());
                n.put("text", cut("Stage " + s.stage() + " " + s.status().toLowerCase().replace('_', ' ')
                        + (s.user() != null ? " by " + s.user() : "") + (s.durationMs() != null ? " in " + s.durationMs() + " ms" : "") + ".", 400));
                n.putArray("evidenceIds").add(ev);
            });
        }

        Optional<String> failureEv = ff == null ? Optional.empty() : firstOf(c, ff.stage(), true).or(() -> firstOf(c, ff.stage(), false));
        if (ff != null && failureEv.isPresent()) {
            ObjectNode where = out.putObject("whereItFailed");
            where.put("stage", ff.stage());
            where.put("component", Objects.requireNonNullElse(ff.component(), Objects.requireNonNullElse(ff.service(), "unknown")));
            where.put("text", cut("The first failure was in " + where.get("component").asText()
                    + (ff.exception() != null ? ", which raised " + ff.exception() : "") + ".", 400));
            where.putArray("evidenceIds").add(failureEv.get());
        } else {
            out.putNull("whereItFailed");
        }

        ArrayNode explanations = out.putArray("anomalyExplanations");
        for (TraceContext.Anomaly a : c.anomalies()) {
            Object stage = a.details() == null ? null : a.details().get("stage");
            Optional<String> ev = stage != null ? firstOf(c, stage.toString(), true).or(() -> firstOf(c, stage.toString(), false))
                    : failureEv.or(() -> c.evidence().stream().map(TraceContext.Evidence::id).findFirst());
            if (ev.isEmpty()) {
                continue;
            }
            ObjectNode n = explanations.addObject();
            n.put("ruleId", a.ruleId());
            n.put("text", cut("Rule " + a.ruleId() + " (" + a.name() + ", " + a.severity() + ") fired" + (stage != null ? " for stage " + stage : "") + ".", 400));
            n.putArray("evidenceIds").add(ev.get());
        }

        if (ff != null && failureEv.isPresent()) {
            ObjectNode cause = out.putObject("likelyCause");
            cause.put("text", cut((ff.exception() != null ? simple(ff.exception()) : "An error") + " in "
                    + where(ff) + " stopped stage " + ff.stage() + ".", 400));
            cause.put("confidence", ff.exception() != null ? "MEDIUM" : "LOW");
            cause.putArray("evidenceIds").add(failureEv.get());
        } else {
            out.putNull("likelyCause");
        }
        ArrayNode checks = out.putArray("suggestedChecks");
        if (ff != null) {
            checks.add(cut("Check the logs of " + where(ff) + " around the first failure.", 200));
        }
        if (!c.anomalies().isEmpty()) {
            checks.add("Review the listed anomalies on the Anomalies page.");
        }
        if (checks.isEmpty()) {
            checks.add("No action needed; the flow shows no failure.");
        }
        out.put("uncertainty", "Generated offline by the fake provider from the evidence in the context only.");
        String text = out.toString();
        return new SummaryResult(text, new Usage(estimate(request.systemPrompt()) + estimate(String.valueOf(c)), estimate(text)), "fake", MODEL);
    }

    @Override
    public ChatTurnResult chat(ChatRequest request) {
        StringBuilder answer = new StringBuilder();
        TraceContext c = request.context();
        if (c != null) {
            TraceContext.Trace t = c.trace();
            answer.append("Flow ").append(t.flowRef()).append(" ended ").append(t.overallStatus()).append('.');
            TraceContext.FirstFailure ff = t.firstFailure();
            if (ff != null) {
                answer.append(" The first failure was in stage ").append(ff.stage()).append(" (").append(where(ff)).append(')');
                firstOf(c, ff.stage(), true).or(() -> firstOf(c, ff.stage(), false))
                        .ifPresent(ev -> answer.append(" [ev:").append(ev.substring(2)).append(']'));
                answer.append('.');
            }
            if (!c.anomalies().isEmpty()) {
                answer.append(" Rules fired: ").append(String.join(", ", c.anomalies().stream().map(TraceContext.Anomaly::ruleId).toList())).append('.');
            }
        } else {
            List<String> called = new ArrayList<>();
            request.tools().stream().filter(tool -> tool.name().equals("list_anomalies")).findFirst().ifPresent(tool -> {
                called.add(tool.call().apply("{}"));
            });
            answer.append(called.isEmpty() ? "No trace is attached to this conversation; open a trace to ask about it."
                    : "Recent anomalies (from list_anomalies): " + cut(called.get(0), 600));
        }
        String text = answer.toString();
        return new ChatTurnResult(text, new Usage(estimate(request.systemPrompt()) + estimate(request.message()), estimate(text)), "fake", MODEL);
    }

    private static Optional<String> firstOf(TraceContext c, String stage, boolean errorsOnly) {
        return c.evidence().stream()
                .filter(e -> stage.equals(e.stage()))
                .filter(e -> !errorsOnly || "ERROR".equals(e.level()))
                .map(TraceContext.Evidence::id).findFirst();
    }

    private static String where(TraceContext.FirstFailure ff) {
        return ff.component() != null ? ff.component() : ff.service() != null ? ff.service() : "an unknown component";
    }

    private static String simple(String exception) {
        int dot = exception.lastIndexOf('.');
        return dot < 0 ? exception : exception.substring(dot + 1);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static int estimate(String s) {
        return s == null ? 0 : Math.max(1, s.length() / 4);
    }
}
