-- Phase 3 (T33): lines sharing an OpenTelemetry trace ID are linked as OBSERVED with link reason TRACE.
ALTER TABLE trace_event_link DROP CONSTRAINT ck_trace_event_link_reason;
ALTER TABLE trace_event_link ADD CONSTRAINT ck_trace_event_link_reason
    CHECK (link_reason IN ('ID', 'REQUEST', 'SESSION', 'SESSION_FALLBACK', 'TRACE', 'THREAD', 'TIME'));
