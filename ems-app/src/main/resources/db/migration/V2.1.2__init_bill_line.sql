-- 子项目 2 · Plan 2.2 · 账单分摊来源明细
-- spec §4.1.6
-- 一行 = 该 bill 的某条来源（rule + 主表/残差/直接归集），用于回答"这 ¥1234 是怎么来的"。
-- bill 删除时级联清理（重写策略：DELETE bill -> CASCADE bill_line -> 重新 INSERT）。

CREATE TABLE bill_line (
    id           BIGSERIAL     PRIMARY KEY,
    bill_id      BIGINT        NOT NULL REFERENCES bill(id)                  ON DELETE CASCADE,
    rule_id      BIGINT        NOT NULL REFERENCES cost_allocation_rule(id) ON DELETE RESTRICT,
    source_label VARCHAR(128)  NOT NULL,
    quantity     NUMERIC(18,4) NOT NULL DEFAULT 0,
    amount       NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (quantity >= 0),
    CHECK (amount   >= 0)
);

CREATE INDEX idx_bill_line_bill ON bill_line (bill_id);
CREATE INDEX idx_bill_line_rule ON bill_line (rule_id);
