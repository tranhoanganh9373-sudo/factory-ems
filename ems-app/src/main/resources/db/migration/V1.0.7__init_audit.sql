CREATE TABLE audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    actor_user_id   BIGINT,
    actor_username  VARCHAR(64),
    action          VARCHAR(32)  NOT NULL,
    resource_type   VARCHAR(32),
    resource_id     VARCHAR(64),
    summary         TEXT,
    detail          JSONB,
    ip              VARCHAR(64),
    user_agent      TEXT,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_occurred_at ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_actor       ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_resource    ON audit_logs (resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_action      ON audit_logs (action, occurred_at DESC);
