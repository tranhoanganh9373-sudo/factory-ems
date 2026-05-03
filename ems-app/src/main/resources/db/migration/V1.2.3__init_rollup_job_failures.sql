-- Rollup 失败重试表：5min → 30min → 2h 三轮指数退避，3 次后停止自动重试并报警。
-- (granularity, bucket_ts, meter_id) 唯一标识一个失败桶；同一桶反复失败递增 attempt。

CREATE TABLE rollup_job_failures (
    id             BIGSERIAL    PRIMARY KEY,
    granularity    VARCHAR(16)  NOT NULL,  -- HOURLY | DAILY | MONTHLY
    bucket_ts      TIMESTAMPTZ  NOT NULL,  -- 该桶的开始时间（hour/day/month 起点）
    meter_id       BIGINT,                 -- NULL = 整个桶级失败（全表扫描类）
    attempt        INTEGER      NOT NULL DEFAULT 1,
    last_error     TEXT,
    next_retry_at  TIMESTAMPTZ  NOT NULL,
    abandoned      BOOLEAN      NOT NULL DEFAULT FALSE,  -- TRUE = 3 次后停止
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (granularity IN ('HOURLY', 'DAILY', 'MONTHLY')),
    CHECK (attempt BETWEEN 1 AND 3)
);

-- 同一桶 + 同一 meter（或同样的 NULL meter）只允许一条活跃记录；abandoned=TRUE 后允许新一轮。
CREATE UNIQUE INDEX uq_rollup_failure_active
    ON rollup_job_failures (granularity, bucket_ts, COALESCE(meter_id, -1))
    WHERE abandoned = FALSE;

CREATE INDEX idx_rollup_failure_next_retry
    ON rollup_job_failures (next_retry_at)
    WHERE abandoned = FALSE;

CREATE INDEX idx_rollup_failure_abandoned
    ON rollup_job_failures (granularity, abandoned, bucket_ts DESC);
