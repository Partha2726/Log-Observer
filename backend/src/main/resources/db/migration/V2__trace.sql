-- Trace persistence (spec section 9 "V8__trace.sql" with the section 9.0 additions; numbered V2
-- because the fresh repository starts after the squashed V1 baseline, see docs/DECISIONS.md).
-- A trace is derived data: it can always be rebuilt from Elasticsearch.

CREATE TABLE trace (
    id                       BIGSERIAL PRIMARY KEY,
    flow_ref                 VARCHAR(64)  NOT NULL UNIQUE,
    flow_id                  VARCHAR(64)  NOT NULL,
    flow_version             INT          NOT NULL,
    -- Demo roles (first three roles of the flow definition); every role is also in trace_actor.
    requester_user_id        VARCHAR(64),
    approver_user_id         VARCHAR(64),
    executor_user_id         VARCHAR(64),
    overall_status           VARCHAR(16)  NOT NULL,
    first_failure_stage      VARCHAR(40),
    first_failure_component  VARCHAR(255),
    first_failure_exception  VARCHAR(255),
    -- Addition: the exact line and gateway status of the first failure, so the graph can mark one node.
    first_failure_doc_id     VARCHAR(64),
    first_failure_http_status INT,
    first_seen               TIMESTAMPTZ  NOT NULL,
    last_seen                TIMESTAMPTZ  NOT NULL,
    amount                   NUMERIC(15,2),
    currency                 CHAR(3),
    counterparty_hash        CHAR(64),
    counterparty_last4       CHAR(4),
    observed_ratio           NUMERIC(3,2),
    excluded_count           INT          NOT NULL DEFAULT 0,
    truncated                BOOLEAN      NOT NULL DEFAULT FALSE,
    assembly_version         INT          NOT NULL,
    config_hash              CHAR(64)     NOT NULL,
    assembled_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_trace_overall_status CHECK (overall_status IN ('COMPLETED', 'FAILED', 'REJECTED', 'INCOMPLETE'))
);

CREATE TABLE trace_stage (
    id           BIGSERIAL PRIMARY KEY,
    trace_id     BIGINT      NOT NULL REFERENCES trace (id) ON DELETE CASCADE,
    stage        VARCHAR(40) NOT NULL,
    stage_order  INT         NOT NULL,
    status       VARCHAR(16) NOT NULL,
    user_id      VARCHAR(64),
    started_at   TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ,
    error_count  INT         NOT NULL DEFAULT 0,
    UNIQUE (trace_id, stage),
    CONSTRAINT ck_trace_stage_status CHECK (status IN ('COMPLETED', 'FAILED', 'REJECTED', 'INCOMPLETE', 'NOT_REACHED'))
);

CREATE TABLE trace_event_link (
    trace_id        BIGINT       NOT NULL REFERENCES trace (id) ON DELETE CASCADE,
    es_doc_id       VARCHAR(64)  NOT NULL,
    -- A flow definition stage, or 'signOut' for an actor's sign-out or expiry line.
    stage           VARCHAR(40)  NOT NULL,
    service         VARCHAR(64)  NOT NULL,
    evidence_class  VARCHAR(12)  NOT NULL,
    confidence      NUMERIC(3,2) NOT NULL,
    link_reason     VARCHAR(20)  NOT NULL,
    event_ts        TIMESTAMPTZ  NOT NULL,
    -- Additions: the parsed fields the graph (section 7.6) and the evidence filters need, so both
    -- are built from Postgres alone. The message text itself stays in Elasticsearch.
    seq             INT          NOT NULL,
    host            VARCHAR(255),
    logger          VARCHAR(255),
    thread          VARCHAR(255),
    request_id      VARCHAR(64),
    session_id      VARCHAR(64),
    user_id         VARCHAR(64),
    event_action    VARCHAR(255),
    http_method     VARCHAR(16),
    url_path        VARCHAR(2048),
    http_status     INT,
    error_type      VARCHAR(255),
    provider_status VARCHAR(8),
    external_ids    VARCHAR(255),
    is_error        BOOLEAN      NOT NULL DEFAULT FALSE,
    is_warning      BOOLEAN      NOT NULL DEFAULT FALSE,
    has_stacktrace  BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (trace_id, es_doc_id),
    CONSTRAINT ck_trace_event_link_class CHECK (evidence_class IN ('OBSERVED', 'CORRELATED')),
    CONSTRAINT ck_trace_event_link_reason CHECK (link_reason IN ('ID', 'REQUEST', 'SESSION', 'SESSION_FALLBACK', 'THREAD', 'TIME'))
);

CREATE TABLE trace_actor (
    trace_id BIGINT      NOT NULL REFERENCES trace (id) ON DELETE CASCADE,
    role     VARCHAR(32) NOT NULL,
    user_id  VARCHAR(64) NOT NULL,
    PRIMARY KEY (trace_id, role)
);

CREATE INDEX idx_trace_actor_user ON trace_actor (user_id);
CREATE INDEX idx_trace_requester ON trace (requester_user_id);
CREATE INDEX idx_trace_approver ON trace (approver_user_id);
CREATE INDEX idx_trace_executor ON trace (executor_user_id);
CREATE INDEX idx_trace_first_seen ON trace (first_seen);
CREATE INDEX idx_trace_flow_id ON trace (flow_id);
CREATE INDEX idx_trace_status_first_seen ON trace (overall_status, first_seen);
CREATE INDEX idx_trace_event_link_stage ON trace_event_link (trace_id, stage, event_ts);
