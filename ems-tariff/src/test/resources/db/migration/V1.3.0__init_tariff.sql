CREATE TABLE tariff_plans (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    energy_type_id  BIGINT       NOT NULL REFERENCES energy_types(id) ON DELETE RESTRICT,
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_tariff_plans_enabled ON tariff_plans (enabled) WHERE enabled = TRUE;
CREATE INDEX idx_tariff_plans_dates   ON tariff_plans (effective_from, effective_to);

CREATE TABLE tariff_periods (
    id              BIGSERIAL    PRIMARY KEY,
    plan_id         BIGINT       NOT NULL REFERENCES tariff_plans(id) ON DELETE CASCADE,
    period_type     VARCHAR(8)   NOT NULL CHECK (period_type IN ('SHARP','PEAK','FLAT','VALLEY')),
    time_start      TIME         NOT NULL,
    time_end        TIME         NOT NULL,
    price_per_unit  NUMERIC(12,4) NOT NULL CHECK (price_per_unit >= 0),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tariff_periods_plan ON tariff_periods (plan_id);
