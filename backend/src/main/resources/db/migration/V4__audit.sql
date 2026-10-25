-- Persistent audit trail (spec sections 8.6 and 11.1; NFR-11). One row per audited action.
-- actor is the tool user's username (or "system" for scheduled work); metadata holds the
-- action's details (for example an anomaly review's old status, new status and note).
CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor       VARCHAR(64) NOT NULL,
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    request_id  VARCHAR(64),
    metadata    JSONB
);

CREATE INDEX idx_audit_event_occurred ON audit_event (occurred_at);
CREATE INDEX idx_audit_event_actor_occurred ON audit_event (actor, occurred_at);
CREATE INDEX idx_audit_event_target ON audit_event (target_type, target_id);
