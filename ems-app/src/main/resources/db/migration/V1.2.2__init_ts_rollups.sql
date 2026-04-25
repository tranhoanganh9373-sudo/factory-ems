-- 预聚合：从 InfluxDB minute 数据滚到 PostgreSQL 的 hour/day/month。
-- org_node_id 冗余以避免每次报表 JOIN meters；当 meters.org_node_id 修改时需在
-- 应用层事务内同步更新这三张表（见 design 文档第 200 行）。
-- 主键设计配合 ON CONFLICT (...) DO UPDATE 实现幂等 upsert。

CREATE TABLE ts_rollup_hourly (
    meter_id     BIGINT       NOT NULL REFERENCES meters(id)    ON DELETE CASCADE,
    org_node_id  BIGINT       NOT NULL REFERENCES org_nodes(id) ON DELETE RESTRICT,
    hour_ts      TIMESTAMPTZ  NOT NULL,
    sum_value    NUMERIC(20, 6) NOT NULL,
    avg_value    NUMERIC(20, 6) NOT NULL,
    max_value    NUMERIC(20, 6) NOT NULL,
    min_value    NUMERIC(20, 6) NOT NULL,
    count        INTEGER        NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (meter_id, hour_ts)
);

CREATE INDEX idx_rollup_hourly_org_node_hour  ON ts_rollup_hourly (org_node_id, hour_ts DESC);
CREATE INDEX idx_rollup_hourly_hour           ON ts_rollup_hourly (hour_ts DESC);

CREATE TABLE ts_rollup_daily (
    meter_id     BIGINT       NOT NULL REFERENCES meters(id)    ON DELETE CASCADE,
    org_node_id  BIGINT       NOT NULL REFERENCES org_nodes(id) ON DELETE RESTRICT,
    day_date     DATE         NOT NULL,
    sum_value    NUMERIC(20, 6) NOT NULL,
    avg_value    NUMERIC(20, 6) NOT NULL,
    max_value    NUMERIC(20, 6) NOT NULL,
    min_value    NUMERIC(20, 6) NOT NULL,
    count        INTEGER        NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (meter_id, day_date)
);

CREATE INDEX idx_rollup_daily_org_node_day  ON ts_rollup_daily (org_node_id, day_date DESC);
CREATE INDEX idx_rollup_daily_day           ON ts_rollup_daily (day_date DESC);

CREATE TABLE ts_rollup_monthly (
    meter_id     BIGINT       NOT NULL REFERENCES meters(id)    ON DELETE CASCADE,
    org_node_id  BIGINT       NOT NULL REFERENCES org_nodes(id) ON DELETE RESTRICT,
    year_month   CHAR(7)      NOT NULL,  -- 'YYYY-MM'
    sum_value    NUMERIC(20, 6) NOT NULL,
    avg_value    NUMERIC(20, 6) NOT NULL,
    max_value    NUMERIC(20, 6) NOT NULL,
    min_value    NUMERIC(20, 6) NOT NULL,
    count        INTEGER        NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (meter_id, year_month),
    CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

CREATE INDEX idx_rollup_monthly_org_node_ym  ON ts_rollup_monthly (org_node_id, year_month DESC);
CREATE INDEX idx_rollup_monthly_ym           ON ts_rollup_monthly (year_month DESC);
