-- 给 meter 加 value_kind：标记每个测点的样本语义。决定后续聚合方式：
--
--   INTERVAL_DELTA    — 单位采样周期内的能耗增量（kWh / cycle）。聚合用 sum()。这是历史默认。
--   CUMULATIVE_ENERGY — 表底数 / odometer（kWh，单调递增）。聚合用 last - first（每桶 difference + sum）。
--                       对应安科瑞 0x003F 这种"吸收有功总电能"寄存器。
--   INSTANT_POWER     — 瞬时功率（W / kW）。聚合用对时间积分（integral）转 kWh。
--                       对应安科瑞 0x0031 这种"总有功功率 P总"寄存器。
--
-- 现网行为 unchanged：所有存量 meter 一律视为 INTERVAL_DELTA（即默认走 sum() 老路径）。
-- 只有新增/编辑时主动选 CUMULATIVE_ENERGY 或 INSTANT_POWER 的 meter 才会走新查询分支。

ALTER TABLE meters
    ADD COLUMN value_kind VARCHAR(32) NOT NULL DEFAULT 'INTERVAL_DELTA';

ALTER TABLE meters
    ADD CONSTRAINT chk_meters_value_kind
    CHECK (value_kind IN ('INTERVAL_DELTA', 'CUMULATIVE_ENERGY', 'INSTANT_POWER'));

COMMENT ON COLUMN meters.value_kind IS
    'Sample semantics: INTERVAL_DELTA (sum), CUMULATIVE_ENERGY (last-first), INSTANT_POWER (integral)';
