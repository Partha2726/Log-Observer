package com.ielop.platform.detection;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.StageDefinition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a rule sees. Trace-scoped rules get {@code trace}; scan rules get the
 * {@code from}/{@code to} window. {@link #positions()} resolves the flow definition's
 * roles and stages by position: the first role creates, the second approves, the
 * last executes; the approval stage is the second role's stage; the final stage is
 * the last stage.
 */
public record RuleInput(FlowDefinition definition, TraceFacts trace, Instant from, Instant to, RuleContext context) {

    public static RuleInput forTrace(FlowDefinition def, TraceFacts trace, RuleContext ctx) {
        return new RuleInput(def, trace, null, null, ctx);
    }

    public static RuleInput forWindow(Instant from, Instant to, RuleContext ctx) {
        return new RuleInput(null, null, from, to, ctx);
    }

    public Positions positions() {
        return new Positions(definition);
    }

    /** The parts of a stored trace rules read (section 10.1). */
    public record TraceFacts(long traceId, String flowRef, String flowId, Instant firstSeen, Instant lastSeen,
                             BigDecimal amount, String currency, String counterpartyHash, Map<String, String> actors,
                             List<StageFact> stages, List<LineFact> lines, String firstFailureDocId) {

        public Optional<StageFact> stage(String name) {
            return stages.stream().filter(s -> s.name().equals(name)).findFirst();
        }

        public List<LineFact> linesOf(String stage) {
            return lines.stream().filter(l -> l.stage().equals(stage)).toList();
        }
    }

    public record StageFact(String name, int order, String status, Instant startedAt, Instant endedAt) {
        public boolean reached() {
            return !"NOT_REACHED".equals(status);
        }

        public Long durationMs() {
            return startedAt == null || endedAt == null ? null : endedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }

    /** One linked line (from {@code trace_event_link}), in trace order. */
    public record LineFact(String docId, String stage, Instant ts, String service, String logger, String eventAction,
                           String userId, Integer httpStatus, String errorType, String providerStatus,
                           List<String> externalIds, boolean isError, boolean observed) {
    }

    /** Roles and stages by position in the flow definition. */
    public record Positions(FlowDefinition def) {

        public String role(int index) {
            int i = index < 0 ? def.roles().size() + index : index;
            return i >= 0 && i < def.roles().size() ? def.roles().get(i) : null;
        }

        public Optional<StageDefinition> stageOfRole(String role) {
            return role == null ? Optional.empty() : def.stages().stream().filter(s -> role.equals(s.actorRole())).findFirst();
        }

        public Optional<StageDefinition> creationStage() {
            return stageOfRole(role(0));
        }

        /** The second role's stage; a flow with one role has none, and R01, R02, R03, R10 are then disabled. */
        public Optional<StageDefinition> approvalStage() {
            return def.roles().size() < 2 ? Optional.empty() : stageOfRole(role(1));
        }

        public StageDefinition finalStage() {
            return def.finalStage();
        }
    }
}
