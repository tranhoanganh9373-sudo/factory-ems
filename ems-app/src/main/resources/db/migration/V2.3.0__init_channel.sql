-- V2.3.0__init_channel.sql
-- 采集器协议扩展：channel 抽象 + 诊断指标
-- spec: docs/superpowers/specs/2026-04-30-collector-protocols-design.md

CREATE TABLE channel (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    protocol        VARCHAR(32)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    is_virtual      BOOLEAN      NOT NULL DEFAULT FALSE,
    protocol_config JSONB        NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_channel_protocol CHECK (
        protocol IN ('MODBUS_TCP','MODBUS_RTU','OPC_UA','MQTT','VIRTUAL')
    )
);
CREATE INDEX idx_channel_protocol ON channel(protocol);
CREATE INDEX idx_channel_enabled  ON channel(enabled) WHERE enabled = TRUE;

ALTER TABLE meters ADD COLUMN IF NOT EXISTS channel_id BIGINT REFERENCES channel(id);

CREATE TABLE collector_metrics (
    channel_id     BIGINT      NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    bucket_at      TIMESTAMPTZ NOT NULL,
    success_cnt    INTEGER     NOT NULL DEFAULT 0,
    failure_cnt    INTEGER     NOT NULL DEFAULT 0,
    avg_latency_ms INTEGER,
    PRIMARY KEY (channel_id, bucket_at)
);
CREATE INDEX idx_collector_metrics_bucket ON collector_metrics(bucket_at);
