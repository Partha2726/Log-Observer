-- Baseline for Log Observer: tool users, roles and the processing job queue.
-- Salvaged (squashed) from the earlier project's V1-V3; final state only.
-- New migrations (trace, detection, assistant, audit) follow from V2.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(50) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username      VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_username ON users (username);
CREATE INDEX idx_users_email ON users (email);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);

INSERT INTO roles (name, description) VALUES
    ('ADMINISTRATOR', 'Administration: manage users, re-assembly, rule scans, audit log.'),
    ('BACKEND_DEVOPS_ENGINEER', 'Developer: search flows, open traces, review anomalies, use the AI assistant.');

CREATE TABLE processing_jobs (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_type          VARCHAR(64) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    attempt_count     INT NOT NULL DEFAULT 0,
    max_attempts      INT NOT NULL DEFAULT 3,
    error_code        VARCHAR(64),
    error_message     TEXT,
    payload_reference TEXT,
    locked_by         VARCHAR(128),
    locked_at         TIMESTAMPTZ,
    lease_expires_at  TIMESTAMPTZ
);

CREATE INDEX idx_processing_jobs_status_created ON processing_jobs (status, created_at);
CREATE INDEX idx_processing_jobs_type_status ON processing_jobs (job_type, status);
CREATE INDEX idx_processing_jobs_lease ON processing_jobs (lease_expires_at) WHERE status = 'RUNNING';
