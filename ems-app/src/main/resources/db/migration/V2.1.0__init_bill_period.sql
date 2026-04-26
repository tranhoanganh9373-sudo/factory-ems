-- 子项目 2 · Plan 2.2 · 账期
-- spec: docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md §4.1.4
-- 状态机: OPEN -> CLOSED -> LOCKED；CLOSED 可再 close（重写）；LOCKED 解锁回 CLOSED 仅 ADMIN + audit。

CREATE TABLE bill_period (
    id            BIGSERIAL    PRIMARY KEY,
    year_month    VARCHAR(7)   NOT NULL UNIQUE,
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN'
                  CHECK (status IN ('OPEN','CLOSED','LOCKED')),
    period_start  TIMESTAMPTZ  NOT NULL,
    period_end    TIMESTAMPTZ  NOT NULL,
    closed_at     TIMESTAMPTZ,
    closed_by     BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    locked_at     TIMESTAMPTZ,
    locked_by     BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CHECK (period_end > period_start),
    CHECK (status <> 'LOCKED' OR (locked_at IS NOT NULL AND locked_by IS NOT NULL)),
    CHECK (status = 'OPEN'    OR closed_at IS NOT NULL)
);

CREATE INDEX idx_bill_period_status      ON bill_period (status);
CREATE INDEX idx_bill_period_year_month  ON bill_period (year_month);
