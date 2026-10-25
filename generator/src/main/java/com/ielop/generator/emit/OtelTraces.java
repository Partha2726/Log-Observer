package com.ielop.generator.emit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Phase 3 (spec section 15, T32): one OpenTelemetry trace per flow and one span per stage, made with the
 * OpenTelemetry SDK. IDs come from a seeded generator, so the same seed gives the same trace and span IDs;
 * the stream is separate from the generator's other streams, so {@code --otel true} changes nothing but the
 * added IDs. Spans start at the first line of their flow or stage (simulated time) and are never exported.
 */
public final class OtelTraces {

    private static final String ROOT = "\u0000flow";

    private final Tracer tracer;
    private final Map<String, Span> roots = new HashMap<>();
    private final Map<String, SpanContext> spans = new HashMap<>();

    public OtelTraces(long seed) {
        SplittableRandom ids = new SplittableRandom(seed ^ 0x07E1_7E1E_0000_0001L);
        IdGenerator generator = new IdGenerator() {
            @Override
            public String generateSpanId() {
                return hex(ids, 8);
            }

            @Override
            public String generateTraceId() {
                return hex(ids, 16);
            }
        };
        this.tracer = SdkTracerProvider.builder().setIdGenerator(generator).build().get("acme-cloud-generator");
    }

    /** The span of a flow's stage (the flow's root span when {@code stage} is null), created on first use. */
    public SpanContext spanFor(String flowRef, String stage, long tsMillis) {
        String key = flowRef + "\u0000" + (stage == null ? ROOT : stage);
        SpanContext existing = spans.get(key);
        if (existing != null) {
            return existing;
        }
        Span root = roots.computeIfAbsent(flowRef, ref -> tracer.spanBuilder("flow " + ref).setNoParent()
                .setStartTimestamp(tsMillis, TimeUnit.MILLISECONDS).startSpan());
        SpanContext ctx = stage == null ? root.getSpanContext()
                : tracer.spanBuilder(stage).setParent(Context.root().with(root)).setStartTimestamp(tsMillis, TimeUnit.MILLISECONDS)
                .startSpan().getSpanContext();
        spans.put(key, ctx);
        return ctx;
    }

    private static String hex(SplittableRandom r, int bytes) {
        byte[] b = new byte[bytes];
        do {
            for (int i = 0; i < bytes; i++) {
                b[i] = (byte) r.nextInt(256);
            }
        } while (allZero(b));
        return HexFormat.of().formatHex(b);
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }
}
