-- 子项目 2 · Plan 2.1 · 成本分摊明细
-- spec §4.1.3
-- 一行 = 一个 (run, rule, target_org, energy_type) 的分摊结果，包含 4 段电价拆分（非电品类 4 段为 0）。
-- run 删除时级联清理；line 不允许 UPDATE。

CREATE TABLE cost_allocation_line (
    id              BIGSERIAL     PRIMARY KEY,
    run_id          BIGINT        NOT NULL REFERENCES cost_allocation_run(id)  ON DELETE CASCADE,
    rule_id         BIGINT        NOT NULL REFERENCES cost_allocation_rule(id) ON DELETE RESTRICT,
    target_org_id   BIGINT        NOT NULL REFERENCES org_nodes(id)            ON DELETE RESTRICT,
    energy_type     VARCHAR(32)   NOT NULL,
    quantity        NUMERIC(18,4) NOT NULL DEFAULT 0,
    amount          NUMERIC(18,4) NOT NULL DEFAULT 0,
    sharp_quantity  NUMERIC(18,4) NOT NULL DEFAULT 0,
    peak_quantity   NUMERIC(18,4) NOT NULL DEFAULT 0,
    flat_quantity   NUMERIC(18,4) NOT NULL DEFAULT 0,
    valley_quantity NUMERIC(18,4) NOT NULL DEFAULT 0,
    sharp_amount    NUMERIC(18,4) NOT NULL DEFAULT 0,
    peak_amount     NUMERIC(18,4) NOT NULL DEFAULT 0,
    flat_amount     NUMERIC(18,4) NOT NULL DEFAULT 0,
    valley_amount   NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (quantity        >= 0),
    CHECK (amount          >= 0),
    CHECK (sharp_quantity  >= 0),
    CHECK (peak_quantity   >= 0),
    CHECK (flat_quantity   >= 0),
    CHECK (valley_quantity >= 0),
    CHECK (sharp_amount    >= 0),
    CHECK (peak_amount     >= 0),
    CHECK (flat_amount     >= 0),
    CHECK (valley_amount   >= 0)
);

CREATE INDEX idx_cost_line_run_org    ON cost_allocation_line (run_id, target_org_id);
CREATE INDEX idx_cost_line_run_rule   ON cost_allocation_line (run_id, rule_id);
CREATE INDEX idx_cost_line_run_energy ON cost_allocation_line (run_id, energy_type);
