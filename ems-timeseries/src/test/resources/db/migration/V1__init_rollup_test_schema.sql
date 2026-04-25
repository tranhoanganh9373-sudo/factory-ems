-- Test-only schema for ems-timeseries rollup IT.
-- 这里只放 rollup IT 真正需要的最小表集合（去掉应用层无关表如 audit / users / refresh tokens）。
-- 与 ems-app/src/main/resources/db/migration/V1.0.1, V1.2.0, V1.2.1, V1.2.2, V1.2.3 保持等价。

CREATE TABLE org_nodes (
    id           BIGSERIAL PRIMARY KEY,
    parent_id    BIGINT REFERENCES org_nodes(id) ON DELETE RESTRICT,
    name         VARCHAR(128) NOT NULL,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    node_type    VARCHAR(32)  NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE energy_types (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL UNIQUE,
    name         VARCHAR(64)  NOT NULL,
    unit         VARCHAR(16)  NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO energy_types (code, name, unit, sort_order) VALUES
    ('ELEC',  'Elec',   'kWh', 10),
    ('WATER', 'Water',  'm3',  20);

CREATE TABLE meters (
    id                   BIGSERIAL    PRIMARY KEY,
    code                 VARCHAR(64)  NOT NULL UNIQUE,
    name                 VARCHAR(128) NOT NULL,
    energy_type_id       BIGINT       NOT NULL REFERENCES energy_types(id) ON DELETE RESTRICT,
    org_node_id          BIGINT       NOT NULL REFERENCES org_nodes(id)    ON DELETE RESTRICT,
    influx_measurement   VARCHAR(64)  NOT NULL,
    influx_tag_key       VARCHAR(64)  NOT NULL,
    influx_tag_value     VARCHAR(128) NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

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

CREATE TABLE ts_rollup_monthly (
    meter_id     BIGINT       NOT NULL REFERENCES meters(id)    ON DELETE CASCADE,
    org_node_id  BIGINT       NOT NULL REFERENCES org_nodes(id) ON DELETE RESTRICT,
    year_month   CHAR(7)      NOT NULL,
    sum_value    NUMERIC(20, 6) NOT NULL,
    avg_value    NUMERIC(20, 6) NOT NULL,
    max_value    NUMERIC(20, 6) NOT NULL,
    min_value    NUMERIC(20, 6) NOT NULL,
    count        INTEGER        NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (meter_id, year_month),
    CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

CREATE TABLE rollup_job_failures (
    id             BIGSERIAL    PRIMARY KEY,
    granularity    VARCHAR(16)  NOT NULL,
    bucket_ts      TIMESTAMPTZ  NOT NULL,
    meter_id       BIGINT,
    attempt        INTEGER      NOT NULL DEFAULT 1,
    last_error     TEXT,
    next_retry_at  TIMESTAMPTZ  NOT NULL,
    abandoned      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (granularity IN ('HOURLY', 'DAILY', 'MONTHLY')),
    CHECK (attempt BETWEEN 1 AND 3)
);

CREATE UNIQUE INDEX uq_rollup_failure_active
    ON rollup_job_failures (granularity, bucket_ts, COALESCE(meter_id, -1))
    WHERE abandoned = FALSE;
