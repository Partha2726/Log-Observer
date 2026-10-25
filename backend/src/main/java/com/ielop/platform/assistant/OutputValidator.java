package com.ielop.platform.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a model's summary before it is stored (section 11.5); the output is untrusted. In order: it
 * decodes as JSON (a JSON object wrapped in a code fence is accepted), matches the {@code sum-1}
 * schema, cites only evidence IDs of the context, explains only the context's rule IDs, states the
 * trace's real status, and stays within the length bounds (part of the schema).
 */
@Component
public class OutputValidator {

    /** The outcome: {@code output} is set when valid, otherwise {@code errors} explain why (sent back on the retry). */
    public record Result(JsonNode output, List<String> errors) {
        public boolean valid() {
            return output != null && errors.isEmpty();
        }
    }

    private final ObjectMapper json;
    private final JsonSchema schema;

    public OutputValidator(ObjectMapper json, Prompts prompts) {
        this.json = json;
        this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(prompts.summarySchema());
    }

    public Result validate(String raw, TraceContext context) {
        JsonNode node;
        try {
            node = json.readTree(unfence(raw));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return new Result(null, List.of("The answer is not valid JSON: " + (e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage())));
        }
        if (node == null || !node.isObject()) {
            return new Result(null, List.of("The answer must be one JSON object"));
        }
        List<String> errors = new ArrayList<>();
        Set<ValidationMessage> schemaErrors = schema.validate(node);
        schemaErrors.stream().map(ValidationMessage::getMessage).sorted().limit(20).forEach(m -> errors.add("Schema: " + m));

        Set<String> evidence = context.evidence().stream().map(TraceContext.Evidence::id).collect(Collectors.toSet());
        node.findValues("evidenceIds").forEach(ids -> ids.forEach(id -> {
            if (!evidence.contains(id.asText())) {
                errors.add("Evidence ID " + id.asText() + " does not exist in the context");
            }
        }));
        Set<String> rules = context.anomalies().stream().map(TraceContext.Anomaly::ruleId).collect(Collectors.toSet());
        node.path("anomalyExplanations").forEach(a -> {
            String rule = a.path("ruleId").asText();
            if (!rules.contains(rule)) {
                errors.add("Rule " + rule + " is not one of the context's anomalies " + rules.stream().sorted().toList());
            }
        });
        String status = node.path("status").asText();
        if (!status.equals(context.trace().overallStatus())) {
            errors.add("status is " + status + " but the trace's overallStatus is " + context.trace().overallStatus());
        }
        Set<String> stages = context.trace().stages().stream().map(TraceContext.Stage::stage).collect(Collectors.toSet());
        node.path("whatHappened").forEach(w -> {
            if (!stages.contains(w.path("stage").asText())) {
                errors.add("Stage " + w.path("stage").asText() + " is not a stage of the flow " + stages.stream().sorted().toList());
            }
        });
        return errors.isEmpty() ? new Result(node, List.of()) : new Result(null, List.copyOf(errors));
    }

    /** Models sometimes wrap JSON in a ```json fence despite instructions; the fence is not content. */
    static String unfence(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("empty answer");
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).strip();
            }
        }
        return s;
    }
}
