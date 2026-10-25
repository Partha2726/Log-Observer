package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Output validation (T26: AI-001, AI-003, AI-004, AI-005 and the malformed-JSON part of AI-002). */
class OutputValidatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final OutputValidator validator = new OutputValidator(json, new Prompts());

    static TraceContext context() {
        return new TraceContext(TraceContext.VERSION,
                new TraceContext.Trace("flow-x", "REQ-20260515-000001", "FAILED", Map.of("requester", "usr-1042"), "usr-1042", "usr-2001", null,
                        List.of(new TraceContext.Stage("Create", "COMPLETED", null, null, 100L, "usr-1042"),
                                new TraceContext.Stage("Approve", "FAILED", null, null, 2000L, "usr-2001")),
                        new TraceContext.FirstFailure("Approve", "svc", "c.a.b.l.LimitCheck", "java.sql.SQLTimeoutException")),
                List.of(new TraceContext.Anomaly("R05", "Stage failed", "HIGH", Map.of("stage", "Approve"))),
                List.of(new TraceContext.Evidence("ev1", "Create", "svc", "t1", "INFO", "OBSERVED", "c.a.w.Handler", null, "created"),
                        new TraceContext.Evidence("ev2", "Approve", "svc", "t2", "ERROR", "OBSERVED", "c.a.b.l.LimitCheck", null, "timeout")),
                new TraceContext.Truncation(0, 0));
    }

    private String fakeOutput() {
        return new FakeAiGateway(json).summarize(new AiGateway.SummaryRequest(context(), "p", null, List.of())).rawText();
    }

    @Test
    void theFakeProvidersOutputIsValid() {
        OutputValidator.Result r = validator.validate(fakeOutput(), context());
        assertThat(r.errors()).isEmpty();
        assertThat(r.valid()).isTrue();
        assertThat(r.output().get("status").asText()).isEqualTo("FAILED");
        assertThat(validator.validate("```json\n" + fakeOutput() + "\n```", context()).valid()).as("a code fence is tolerated").isTrue();
    }

    @Test
    void malformedJsonIsRejected() {
        assertThat(validator.validate("{\"headline\": ", context()).errors()).singleElement().asString().contains("not valid JSON");
        assertThat(validator.validate("[1, 2]", context()).errors()).containsExactly("The answer must be one JSON object");
        assertThat(validator.validate("", context()).valid()).isFalse();
    }

    @Test
    void fabricatedEvidenceIdsAreRejected() throws Exception {
        ObjectNode o = (ObjectNode) json.readTree(fakeOutput());
        ((ObjectNode) o.get("whereItFailed")).putArray("evidenceIds").add("ev9");
        assertThat(validator.validate(o.toString(), context()).errors()).contains("Evidence ID ev9 does not exist in the context");
    }

    @Test
    void fabricatedRuleIdsAreRejected() throws Exception {
        ObjectNode o = (ObjectNode) json.readTree(fakeOutput());
        ObjectNode extra = o.withArray("anomalyExplanations").addObject();
        extra.put("ruleId", "R01");
        extra.put("text", "Self-approval");
        extra.putArray("evidenceIds").add("ev1");
        assertThat(validator.validate(o.toString(), context()).errors()).anyMatch(e -> e.startsWith("Rule R01 is not one of the context's anomalies"));
    }

    @Test
    void aStatusOtherThanTheTracesIsRejected() throws Exception {
        ObjectNode o = (ObjectNode) json.readTree(fakeOutput());
        o.put("status", "COMPLETED");
        assertThat(validator.validate(o.toString(), context()).errors()).contains("status is COMPLETED but the trace's overallStatus is FAILED");
    }

    @Test
    void schemaAndLengthViolationsAreRejected() throws Exception {
        ObjectNode o = (ObjectNode) json.readTree(fakeOutput());
        o.put("headline", "x".repeat(141));
        o.put("unexpected", true);
        o.remove("uncertainty");
        List<String> errors = validator.validate(o.toString(), context()).errors();
        assertThat(errors).anyMatch(e -> e.contains("headline")).anyMatch(e -> e.contains("unexpected")).anyMatch(e -> e.contains("uncertainty"));
        ObjectNode wrongStage = (ObjectNode) json.readTree(fakeOutput());
        ((ObjectNode) wrongStage.withArray("whatHappened").get(0)).put("stage", "Payment");
        assertThat(validator.validate(wrongStage.toString(), context()).errors()).anyMatch(e -> e.startsWith("Stage Payment is not a stage"));
    }
}
