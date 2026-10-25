package com.ielop.platform.trace;

/** Published inside the assembly transaction; listeners (trace-scoped rules) run after it commits. */
public record TraceAssembledEvent(long traceId, String flowRef) {
}
