-- ems-alarm: 采集中断告警
-- spec: docs/superpowers/specs/2026-04-29-acquisition-alarm-design.md

CREATE TABLE alarms (
    id              BIGSERIAL PRIMARY KEY,
    device_id       BIGINT       NOT NULL,
    device_type     VARCHAR(32)  NOT NULL,
    alarm_type      VARCHAR(32)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'WARNING',
    status          VARCHAR(16)  NOT NULL,
    triggered_at    TIMESTAMPTZ  NOT NULL,
    acked_at        TIMESTAMPTZ,
    acked_by        BIGINT,
    resolved_at     TIMESTAMPTZ,
    resolved_reason VARCHAR(32),
    last_seen_at    TIMESTAMPTZ,
    detail          JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_alarms_device_status ON alarms (device_id, status);
CREATE INDEX idx_alarms_status_trig   ON alarms (status, triggered_at DESC);
CREATE INDEX idx_alarms_triggered_at  ON alarms (triggered_at DESC);

CREATE TABLE alarm_rules_override (
    device_id              BIGINT      PRIMARY KEY,
    silent_timeout_seconds INT,
    consecutive_fail_count INT,
    maintenance_mode       BOOLEAN     NOT NULL DEFAULT FALSE,
    maintenance_note       VARCHAR(255),
    updated_at             TIMESTAMPTZ NOT NULL,
    updated_by             BIGINT
);

CREATE TABLE webhook_config (
    id            BIGSERIAL    PRIMARY KEY,
    enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    url           VARCHAR(512) NOT NULL,
    secret        VARCHAR(255),
    adapter_type  VARCHAR(32)  NOT NULL DEFAULT 'GENERIC_JSON',
    timeout_ms    INT          NOT NULL DEFAULT 5000,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    BIGINT
);

CREATE TABLE webhook_delivery_log (
    id              BIGSERIAL    PRIMARY KEY,
    alarm_id        BIGINT       NOT NULL,
    attempts        INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    last_error      VARCHAR(512),
    response_status INT,
    response_ms     INT,
    payload         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wdl_alarm  ON webhook_delivery_log (alarm_id);
CREATE INDEX idx_wdl_status ON webhook_delivery_log (status, created_at DESC);

CREATE TABLE alarm_inbox (
    id          BIGSERIAL    PRIMARY KEY,
    alarm_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_inbox_user_unread ON alarm_inbox (user_id, read_at);
