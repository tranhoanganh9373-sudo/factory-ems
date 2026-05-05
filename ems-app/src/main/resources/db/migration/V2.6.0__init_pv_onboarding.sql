-- V2.6.0__init_pv_onboarding.sql
-- v1.2.0 PV onboarding：在 meters 加 role/energy_source/flow_direction
-- 三个分类字段；新建 carbon_factor、feed_in_tariff 两张参数表。
-- spec: docs/superpowers/specs/2026-05-04-v1.1.5-topology-alarm-and-v1.2.0-pv-design.md §2

ALTER TABLE meters
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'CONSUME',
    ADD COLUMN energy_source VARCHAR(16) NOT NULL DEFAULT 'GRID',
    ADD COLUMN flow_direction VARCHAR(16) NOT NULL DEFAULT 'IMPORT';

ALTER TABLE meters
    ADD CONSTRAINT chk_meters_role CHECK (role IN ('CONSUME', 'GENERATE', 'GRID_TIE')),
    ADD CONSTRAINT chk_meters_energy_source CHECK (energy_source IN ('GRID', 'SOLAR', 'WIND', 'STORAGE')),
    ADD CONSTRAINT chk_meters_flow_direction CHECK (flow_direction IN ('IMPORT', 'EXPORT'));

CREATE INDEX idx_meters_role          ON meters(role)          WHERE role <> 'CONSUME';
CREATE INDEX idx_meters_energy_source ON meters(energy_source) WHERE energy_source <> 'GRID';

-- 碳排因子（按区域 + 能源类型 + 生效日）
CREATE TABLE carbon_factor (
    id                 BIGSERIAL    PRIMARY KEY,
    region             VARCHAR(64)  NOT NULL,
    energy_source      VARCHAR(16)  NOT NULL,
    effective_from     DATE         NOT NULL,
    factor_kg_per_kwh  NUMERIC(10,4) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (region, energy_source, effective_from),
    CHECK (energy_source IN ('GRID', 'SOLAR', 'WIND', 'STORAGE')),
    CHECK (factor_kg_per_kwh >= 0)
);

-- 上网电价（分时段）
CREATE TABLE feed_in_tariff (
    id              BIGSERIAL    PRIMARY KEY,
    region          VARCHAR(64)  NOT NULL,
    energy_source   VARCHAR(16)  NOT NULL,
    period_type     VARCHAR(8)   NOT NULL,
    effective_from  DATE         NOT NULL,
    price           NUMERIC(10,4) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (region, energy_source, period_type, effective_from),
    CHECK (energy_source IN ('GRID', 'SOLAR', 'WIND', 'STORAGE')),
    CHECK (period_type IN ('PEAK', 'FLAT', 'VALLEY', 'TIP', 'SHARP')),
    CHECK (price >= 0)
);

-- Seed 全国默认上网电价（光伏）
INSERT INTO feed_in_tariff (region, energy_source, period_type, effective_from, price) VALUES
    ('CN', 'SOLAR', 'PEAK',   DATE '2020-01-01', 0.4500),
    ('CN', 'SOLAR', 'FLAT',   DATE '2020-01-01', 0.4000),
    ('CN', 'SOLAR', 'VALLEY', DATE '2020-01-01', 0.3500);

-- Seed 全国默认碳排因子（电网 vs 光伏）— 数值参考国家发改委 2024 公开标准
INSERT INTO carbon_factor (region, energy_source, effective_from, factor_kg_per_kwh) VALUES
    ('CN', 'GRID',    DATE '2020-01-01', 0.5810),
    ('CN', 'SOLAR',   DATE '2020-01-01', 0.0480),
    ('CN', 'WIND',    DATE '2020-01-01', 0.0260),
    ('CN', 'STORAGE', DATE '2020-01-01', 0.0000);
