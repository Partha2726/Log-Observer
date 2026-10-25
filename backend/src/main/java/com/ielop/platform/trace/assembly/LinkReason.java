package com.ielop.platform.trace.assembly;

/** Why a line is linked to a trace (section 7.1, {@code trace_event_link.link_reason}). */
public enum LinkReason {
    ID,
    REQUEST,
    SESSION,
    SESSION_FALLBACK,
    /** Phase 3: shares the OpenTelemetry trace ID of an OBSERVED line (OBSERVED). */
    TRACE,
    THREAD,
    TIME
}
