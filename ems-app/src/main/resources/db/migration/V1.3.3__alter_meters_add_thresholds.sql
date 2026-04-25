-- 测点阈值：用于平面图热力图着色（无阈值则走中性色）
-- 单位与 energy_type 配套（电：kW 或 kWh，看面板语义；MVP 不强约束）

ALTER TABLE meters
    ADD COLUMN warning_upper NUMERIC(18,4),
    ADD COLUMN warning_lower NUMERIC(18,4);

ALTER TABLE meters
    ADD CONSTRAINT chk_meters_threshold_order
    CHECK (
        warning_upper IS NULL
        OR warning_lower IS NULL
        OR warning_upper >= warning_lower
    );
