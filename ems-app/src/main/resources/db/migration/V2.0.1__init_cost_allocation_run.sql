-- 子项目 2 · Plan 2.1 · 成本分摊批次
-- spec §4.1.2
-- 状态机: PENDING -> RUNNING -> SUCCESS / FAILED；同账期写入 SUCCESS 时事务内把老 SUCCESS 标 SUPERSEDED。

CREATE TABLE cost_allocation_run (
    id                 BIGSERIAL    PRIMARY KEY,
    period_start       TIMESTAMPTZ  NOT NULL,
    period_end         TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCESS','FAILED','SUPERSEDED')),
    algorithm_version  VARCHAR(16)  NOT NULL DEFAULT 'v1',
    total_amount       NUMERIC(18,4),
    rule_ids           BIGINT[],
    created_by         BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    error_message      TEXT,
    CHECK (period_end > period_start)
);

CREATE INDEX idx_cost_run_period   ON cost_allocation_run (period_start, period_end);
CREATE INDEX idx_cost_run_status   ON cost_allocation_run (status);
CREATE INDEX idx_cost_run_created  ON cost_allocation_run (created_at DESC);
-- 同账期最多 1 条 SUCCESS（partial unique index）—— 防止两个并发 run 都写 SUCCESS。
-- 重跑通过 status=SUPERSEDED 释放该 slot。
CREATE UNIQUE INDEX uq_cost_run_period_success
    ON cost_allocation_run (period_start, period_end)
    WHERE status = 'SUCCESS';
