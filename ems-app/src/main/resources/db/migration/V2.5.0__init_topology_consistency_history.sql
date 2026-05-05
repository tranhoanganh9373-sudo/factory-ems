-- v1.1.5 — hourly trend storage for topology-consistency check
CREATE TABLE topology_consistency_history (
    id              BIGSERIAL PRIMARY KEY,
    parent_meter_id BIGINT          NOT NULL,
    energy_type     VARCHAR(16)     NOT NULL,
    parent_reading  NUMERIC(20, 4)  NOT NULL,
    children_sum    NUMERIC(20, 4)  NOT NULL,
    children_count  INT             NOT NULL,
    residual        NUMERIC(20, 4)  NOT NULL,
    residual_ratio  NUMERIC(8, 5),
    severity        VARCHAR(16)     NOT NULL,
    sampled_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_topo_hist_parent_sampled
    ON topology_consistency_history (parent_meter_id, sampled_at);

CREATE INDEX ix_topo_hist_sampled
    ON topology_consistency_history (sampled_at);

COMMENT ON TABLE topology_consistency_history IS
    'v1.1.5 hourly snapshot of parent vs children-sum residual for trend display + alarm fact-checking';
