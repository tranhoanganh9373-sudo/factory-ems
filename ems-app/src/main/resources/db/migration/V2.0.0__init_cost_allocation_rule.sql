-- 子项目 2 · Plan 2.1 · 成本分摊规则
-- spec: docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md §4.1.1
-- 一条 rule 描述"如何把一个 source_meter 的能耗 / 费用 分给一组 target_org_ids"。
-- weights JSONB 结构按 algorithm 不同（见 spec / Plan 2.1 §9）。

CREATE TABLE cost_allocation_rule (
    id              BIGSERIAL    PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    energy_type     VARCHAR(32)  NOT NULL CHECK (energy_type IN ('ELEC','WATER','GAS','STEAM','OIL')),
    algorithm       VARCHAR(32)  NOT NULL CHECK (algorithm IN ('DIRECT','PROPORTIONAL','RESIDUAL','COMPOSITE')),
    source_meter_id BIGINT       NOT NULL REFERENCES meters(id) ON DELETE RESTRICT,
    target_org_ids  BIGINT[]     NOT NULL,
    weights         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    priority        INT          NOT NULL DEFAULT 100,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (cardinality(target_org_ids) > 0)
);

CREATE INDEX idx_cost_rule_enabled         ON cost_allocation_rule (enabled) WHERE enabled = TRUE;
CREATE INDEX idx_cost_rule_energy_type     ON cost_allocation_rule (energy_type);
CREATE INDEX idx_cost_rule_source_meter    ON cost_allocation_rule (source_meter_id);
CREATE INDEX idx_cost_rule_priority        ON cost_allocation_rule (priority);
CREATE INDEX idx_cost_rule_effective_dates ON cost_allocation_rule (effective_from, effective_to);
