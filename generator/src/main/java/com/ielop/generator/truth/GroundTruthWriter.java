package com.ielop.generator.truth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.ielop.generator.emit.Event;
import com.ielop.generator.emit.LineSink;
import com.ielop.generator.flow.FlowDefinition;
import com.ielop.generator.model.Flow;
import com.ielop.generator.model.Incident;
import com.ielop.generator.model.User;
import com.ielop.generator.model.UserDirectory;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Writes the ground-truth files of spec section 5.6 as JSON lines:
 * {@code flows.jsonl}, {@code lines.jsonl} (one row per emitted event, in the
 * order written), {@code users.jsonl} and {@code incidents.jsonl} (non-flow
 * scenarios; empty until T19 adds them).
 */
public final class GroundTruthWriter implements LineSink, Closeable {

    private static final JsonFactory JSON = new JsonFactory();

    private final FlowDefinition definition;
    private final BufferedWriter lines;
    private final BufferedWriter flows;
    private final BufferedWriter incidents;
    private final Path dir;

    public GroundTruthWriter(Path dir, FlowDefinition definition) throws IOException {
        this.dir = dir;
        this.definition = definition;
        Files.createDirectories(dir);
        this.lines = Files.newBufferedWriter(dir.resolve("lines.jsonl"), StandardCharsets.UTF_8);
        this.flows = Files.newBufferedWriter(dir.resolve("flows.jsonl"), StandardCharsets.UTF_8);
        this.incidents = Files.newBufferedWriter(dir.resolve("incidents.jsonl"), StandardCharsets.UTF_8);
    }

    @Override
    public void accept(Event e, String file, int lineNo) {
        try (JsonGenerator g = JSON.createGenerator(lines)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            g.writeStartObject();
            g.writeStringField("file", file);
            g.writeNumberField("lineNo", lineNo);
            g.writeStringField("flowRef", e.truthFlowRef());
            g.writeStringField("requestId", e.truthRequestId());
            g.writeStringField("stage", e.truthStage());
            g.writeBooleanField("isNoise", e.noise());
            g.writeEndObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        newline(lines);
    }

    public void flow(Flow f) {
        try (JsonGenerator g = JSON.createGenerator(flows)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            g.writeStartObject();
            g.writeStringField("flowRef", f.flowRef());
            g.writeStringField("flowId", definition.id());
            g.writeStringField("scenario", f.scenario().name());
            g.writeStringField("startedAt", Instant.ofEpochMilli(f.startMillis()).toString());
            g.writeStringField("requester", f.requester().id());
            g.writeStringField("approver", f.approver() != null ? f.approver().id() : null);
            g.writeStringField("executor", f.executor() != null ? f.executor().id() : null);
            g.writeObjectFieldStart("stages");
            for (String stage : definition.stages()) {
                g.writeStringField(stage, f.stageStatus().getOrDefault(stage, "NOT_REACHED"));
            }
            g.writeEndObject();
            g.writeStringField("overallStatus", f.overallStatus(definition.executeStage()));
            g.writeStringField("expectedFailingStage", f.failingStage());
            g.writeStringField("expectedFailingComponent", f.failingComponent());
            g.writeNumberField("amount", f.amount());
            g.writeStringField("currency", f.currency());
            g.writeEndObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        newline(flows);
    }

    /** One non-flow scenario (brute force, concurrent sessions, probing, scanner) with its window. */
    public void incident(Incident i) {
        try (JsonGenerator g = JSON.createGenerator(incidents)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            g.writeStartObject();
            g.writeStringField("scenario", i.scenario().name());
            g.writeStringField("userId", i.userId());
            g.writeStringField("clientIp", i.clientIp());
            g.writeStringField("windowStart", Instant.ofEpochMilli(i.startMillis()).toString());
            g.writeStringField("windowEnd", Instant.ofEpochMilli(i.endMillis()).toString());
            g.writeStringField("relatedFlowRef", i.relatedFlowRef());
            g.writeNumberField("events", i.events());
            g.writeStringField("expectedRule", i.expectedRule());
            g.writeEndObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        newline(incidents);
    }

    public void users(UserDirectory users) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("users.jsonl"), StandardCharsets.UTF_8)) {
            for (User u : users.all()) {
                try (JsonGenerator g = JSON.createGenerator(w)) {
                    g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
                    g.writeStartObject();
                    g.writeStringField("userId", u.id());
                    g.writeStringField("name", u.name());
                    g.writeStringField("email", u.email());
                    g.writeStringField("roleGroup", u.rolesLabel());
                    g.writeArrayFieldStart("roles");
                    for (Map.Entry<String, Boolean> role : Map.of(
                            definition.roles().get(0), u.requester(),
                            definition.roles().get(1), u.canApprove(),
                            definition.roles().get(2), u.canApprove()).entrySet().stream()
                            .sorted(Map.Entry.comparingByKey()).toList()) {
                        if (role.getValue()) {
                            g.writeString(role.getKey());
                        }
                    }
                    g.writeEndArray();
                    g.writeBooleanField("hasApprovePermission", u.canApprove());
                    if (u.approvalLimit() != null) {
                        g.writeNumberField("limitAmount", u.approvalLimit());
                    } else {
                        g.writeNullField("limitAmount");
                    }
                    g.writeStringField("currency", "USD");
                    g.writeEndObject();
                }
                newline(w);
            }
        }
    }

    @Override
    public void close() throws IOException {
        lines.close();
        flows.close();
        incidents.close();
    }

    private static void newline(BufferedWriter w) {
        try {
            w.write('\n');
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
