-- 子项目 2 · Plan 2.2 · 账单
-- spec §4.1.5
-- 一行 = 一个 (period, org, energy) 的账单聚合。run_id 指向当时引用的 SUCCESS 分摊批次。
-- 唯一键 (period_id, org_node_id, energy_type) 保证账单不重复；重写时先 DELETE 再 INSERT（bill_line 级联）。
-- LOCKED 账期的 bill 由 service 层拒绝任何 UPDATE/DELETE（数据库层不强制，留给应用语义）。

CREATE TABLE bill (
    id              BIGSERIAL     PRIMARY KEY,
    period_id       BIGINT        NOT NULL REFERENCES bill_period(id)         ON DELETE RESTRICT,
    run_id          BIGINT        NOT NULL REFERENCES cost_allocation_run(id) ON DELETE RESTRICT,
    org_node_id     BIGINT        NOT NULL REFERENCES org_nodes(id)           ON DELETE RESTRICT,
    energy_type     VARCHAR(32)   NOT NULL CHECK (energy_type IN ('ELEC','WATER','GAS','STEAM','OIL')),
    quantity        NUMERIC(18,4) NOT NULL DEFAULT 0,
    amount          NUMERIC(18,4) NOT NULL DEFAULT 0,
    sharp_amount    NUMERIC(18,4) NOT NULL DEFAULT 0,
    peak_amount     NUMERIC(18,4) NOT NULL DEFAULT 0,
    flat_amount     NUMERIC(18,4) NOT NULL DEFAULT 0,
    valley_amount   NUMERIC(18,4) NOT NULL DEFAULT 0,
    production_qty  NUMERIC(18,4),
    unit_cost       NUMERIC(18,6),
    unit_intensity  NUMERIC(18,6),
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (period_id, org_node_id, energy_type),
    CHECK (quantity      >= 0),
    CHECK (amount        >= 0),
    CHECK (sharp_amount  >= 0),
    CHECK (peak_amount   >= 0),
    CHECK (flat_amount   >= 0),
    CHECK (valley_amount >= 0)
);

CREATE INDEX idx_bill_period_org    ON bill (period_id, org_node_id);
CREATE INDEX idx_bill_period_energy ON bill (period_id, energy_type);
CREATE INDEX idx_bill_run           ON bill (run_id);
CREATE INDEX idx_bill_org           ON bill (org_node_id);
