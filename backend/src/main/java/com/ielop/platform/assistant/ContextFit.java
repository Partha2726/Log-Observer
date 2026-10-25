package com.ielop.platform.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shrinks a trace context to a provider's {@code maxContextTokens} (estimated as characters / 4, as in
 * {@link TraceContextBuilder}), keeping the output budget free. Evidence is dropped last-priority first:
 * lines that are neither ERROR nor WARN, the latest first, then the rest. Kept lines keep their IDs, so
 * the evidence map and the validator still apply; the drop is added to {@code truncated.evidenceOmitted}.
 */
final class ContextFit {

    private ContextFit() {
    }

    static int tokens(int chars) {
        return chars / 4 + 1;
    }

    /** {@code otherChars}: the system prompt, schema and any retry errors sent with the context. */
    static TraceContext fit(TraceContext c, int otherChars, int maxContextTokens, int maxOutputTokens) {
        if (c == null || fits(c, otherChars, maxContextTokens, maxOutputTokens)) {
            return c;
        }
        List<TraceContext.Evidence> keep = new ArrayList<>(c.evidence());
        List<TraceContext.Evidence> dropOrder = new ArrayList<>(keep);
        dropOrder.sort(Comparator.comparing((TraceContext.Evidence e) -> "ERROR".equals(e.level()) || "WARN".equals(e.level()))
                .thenComparing(TraceContext.Evidence::ts, Comparator.reverseOrder()));
        int dropped = 0;
        for (TraceContext.Evidence e : dropOrder) {
            TraceContext candidate = with(c, keep, dropped);
            if (fits(candidate, otherChars, maxContextTokens, maxOutputTokens)) {
                return candidate;
            }
            keep.remove(e);
            dropped++;
        }
        return with(c, keep, dropped);
    }

    /** A chat request whose system prompt holds a rendered context: re-render it shrunk; also drop the oldest history. */
    static AiGateway.ChatRequest fit(AiGateway.ChatRequest r, int maxContextTokens, int maxOutputTokens) {
        int historyChars = r.history().stream().mapToInt(m -> m.content().length()).sum();
        int total = r.systemPrompt().length() + historyChars + r.message().length() + toolChars(r);
        if (tokens(total) + maxOutputTokens <= maxContextTokens) {
            return r;
        }
        String system = r.systemPrompt();
        TraceContext context = r.context();
        if (context != null) {
            String rendered = PromptText.context(context);
            int idx = system.indexOf(rendered);
            if (idx >= 0) {
                int others = system.length() - rendered.length() + historyChars + r.message().length() + toolChars(r);
                context = fit(context, others, maxContextTokens, maxOutputTokens);
                system = system.substring(0, idx) + PromptText.context(context) + system.substring(idx + rendered.length());
            }
        }
        List<AiGateway.ChatMessage> history = new ArrayList<>(r.history());
        while (!history.isEmpty() && tokens(system.length() + history.stream().mapToInt(m -> m.content().length()).sum()
                + r.message().length() + toolChars(r)) + maxOutputTokens > maxContextTokens) {
            history.remove(0);
        }
        return new AiGateway.ChatRequest(system, history, r.message(), context, r.tools());
    }

    private static int toolChars(AiGateway.ChatRequest r) {
        return r.tools().stream().mapToInt(t -> t.name().length() + t.description().length() + t.inputSchema().length()).sum();
    }

    private static boolean fits(TraceContext c, int otherChars, int maxContextTokens, int maxOutputTokens) {
        return tokens(PromptText.context(c).length() + otherChars) + maxOutputTokens <= maxContextTokens;
    }

    private static TraceContext with(TraceContext c, List<TraceContext.Evidence> keep, int dropped) {
        List<TraceContext.Evidence> ordered = c.evidence().stream().filter(keep::contains).toList();
        TraceContext.Truncation t = c.truncated() == null ? new TraceContext.Truncation(0, 0) : c.truncated();
        return new TraceContext(c.contextVersion(), c.trace(), c.anomalies(), ordered,
                new TraceContext.Truncation(t.evidenceOmitted() + dropped, t.textsCut()));
    }
}
