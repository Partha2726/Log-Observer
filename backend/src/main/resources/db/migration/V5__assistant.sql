-- AI summaries, assistant conversations and token budgets (spec sections 9 and 11).
CREATE TABLE ai_summary (
    id             BIGSERIAL PRIMARY KEY,
    trace_id       BIGINT       NOT NULL REFERENCES trace (id) ON DELETE CASCADE,
    status         VARCHAR(12)  NOT NULL,
    context_hash   CHAR(64)     NOT NULL,
    prompt_version VARCHAR(16)  NOT NULL,
    provider       VARCHAR(32)  NOT NULL,
    model          VARCHAR(64)  NOT NULL,
    output         JSONB,
    error_code     VARCHAR(32),
    input_tokens   INT,
    output_tokens  INT,
    requested_by   VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ,
    -- Additions: the AI_SUMMARY job that produces the row, the sanitized raw text of an invalid output (11.5),
    -- and the server-side map of the context's evidence IDs (ev1…evN) to Elasticsearch document IDs.
    job_id         UUID,
    raw_output     TEXT,
    evidence_map   JSONB,
    CONSTRAINT ck_ai_summary_status CHECK (status IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_ai_summary_trace_hash ON ai_summary (trace_id, context_hash, prompt_version);

CREATE TABLE ai_conversation (
    id         UUID PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    flow_ref   VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ai_conversation_user_created ON ai_conversation (user_id, created_at);

CREATE TABLE ai_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES ai_conversation (id) ON DELETE CASCADE,
    role            VARCHAR(12) NOT NULL,
    content         TEXT        NOT NULL,
    citations       JSONB,
    tool_calls      JSONB,
    input_tokens    INT,
    output_tokens   INT,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ai_message_role CHECK (role IN ('user', 'assistant'))
);
CREATE INDEX idx_ai_message_conversation_created ON ai_message (conversation_id, created_at);

CREATE TABLE ai_token_usage (
    user_id VARCHAR(64) NOT NULL,
    day     DATE        NOT NULL,
    tokens  BIGINT      NOT NULL,
    PRIMARY KEY (user_id, day)
);
