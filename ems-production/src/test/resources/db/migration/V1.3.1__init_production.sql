-- 班次字典（全工厂统一） + 产量人工填报
-- 班次允许跨零点：time_start > time_end 表示跨日（22:00 → 06:00）

CREATE TABLE shifts (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(64)  NOT NULL,
    time_start  TIME         NOT NULL,
    time_end    TIME         NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 0,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_shifts_enabled ON shifts (enabled) WHERE enabled = TRUE;

-- 产量填报：组织节点 × 班次 × 日期 × 产品 × 数量
-- 班次跨零点时归属起始日（22:00 入班的那天）
CREATE TABLE production_entries (
    id            BIGSERIAL    PRIMARY KEY,
    org_node_id   BIGINT       NOT NULL REFERENCES org_nodes(id) ON DELETE RESTRICT,
    shift_id      BIGINT       NOT NULL REFERENCES shifts(id)    ON DELETE RESTRICT,
    entry_date    DATE         NOT NULL,
    product_code  VARCHAR(64)  NOT NULL,
    quantity      NUMERIC(18,4) NOT NULL CHECK (quantity >= 0),
    unit          VARCHAR(16)  NOT NULL,
    remark        VARCHAR(255),
    created_by    BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (org_node_id, shift_id, entry_date, product_code)
);

CREATE INDEX idx_prod_entries_date     ON production_entries (entry_date);
CREATE INDEX idx_prod_entries_org_date ON production_entries (org_node_id, entry_date);
