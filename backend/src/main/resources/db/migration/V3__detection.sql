-- Detection (spec section 9 "V9__detection.sql" with the section 9.0 additions; numbered V3).

CREATE TABLE anomaly (
    id               BIGSERIAL PRIMARY KEY,
    rule_id          VARCHAR(8)   NOT NULL,
    severity         VARCHAR(8)   NOT NULL,
    trace_id         BIGINT REFERENCES trace (id),
    user_id          VARCHAR(64),
    client_ip        INET,
    detected_at      TIMESTAMPTZ  NOT NULL,
    window_start     TIMESTAMPTZ,
    window_end       TIMESTAMPTZ,
    details          JSONB        NOT NULL,
    evidence_doc_ids TEXT[]       NOT NULL,
    status           VARCHAR(12)  NOT NULL DEFAULT 'NEW',
    reviewed_by      VARCHAR(64),
    review_note      TEXT,
    reviewed_at      TIMESTAMPTZ,
    version          INT          NOT NULL DEFAULT 0,
    -- rule + subject + window: makes re-scans idempotent
    dedup_key        VARCHAR(128) NOT NULL UNIQUE,
    -- Addition: the flowRef of trace-scoped findings, for filtering without a join (anomaly list, section 8.8).
    flow_ref         VARCHAR(64),
    CONSTRAINT ck_anomaly_status CHECK (status IN ('NEW', 'CONFIRMED', 'DISMISSED')),
    CONSTRAINT ck_anomaly_severity CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
);

-- Feeds R10: approval limits imported from the generator's users.jsonl.
CREATE TABLE user_limit (
    user_id      VARCHAR(64)   PRIMARY KEY,
    roles        TEXT[]        NOT NULL,
    limit_amount NUMERIC(15,2) NOT NULL,
    currency     CHAR(3)       NOT NULL DEFAULT 'USD',
    imported_at  TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_anomaly_rule_detected ON anomaly (rule_id, detected_at);
CREATE INDEX idx_anomaly_status_severity ON anomaly (status, severity);
CREATE INDEX idx_anomaly_user ON anomaly (user_id);
CREATE INDEX idx_anomaly_trace ON anomaly (trace_id);
CREATE INDEX idx_anomaly_flow_ref ON anomaly (flow_ref);
-- R09 baselines: durations of one stage over the last 7 days.
CREATE INDEX idx_trace_stage_stage_started ON trace_stage (stage, started_at);
