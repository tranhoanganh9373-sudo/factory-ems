-- 子项目 2 · Plan 2.1 · 给 org_nodes 加 area_m2 / headcount 两个可选字段
-- spec §5.2 PROPORTIONAL 算法的 AREA / HEADCOUNT 权重来源。
-- nullable，老数据不强制填；权重解析时遇到 null 视作 0、回退到 FIXED 平均分（见 WeightResolver）。

ALTER TABLE org_nodes
    ADD COLUMN area_m2  NUMERIC(12, 2),
    ADD COLUMN headcount INTEGER;
