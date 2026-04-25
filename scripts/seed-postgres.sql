-- scripts/seed-postgres.sql
-- Seeds demo org_nodes, energy_types, and meters for factory-ems.
-- Idempotent: all inserts use ON CONFLICT DO NOTHING.
--
-- Org hierarchy:
--   Factory (FACTORY)
--   ├── LineA  (LINE)
--   │   ├── Cell-1 (CELL)
--   │   └── Cell-2 (CELL)
--   └── LineB  (LINE)
--       └── Cell-3 (CELL)
--
-- Meters:
--   M-1 ELEC  kWh  → Cell-1
--   M-2 ELEC  kWh  → Cell-2
--   M-3 WATER m3   → Cell-1
--   M-4 WATER m3   → Cell-3
--   M-5 GAS   m3   → LineA
--   M-6 STEAM t    → LineB

BEGIN;

-- ─── org_nodes ────────────────────────────────────────────────────────────────

INSERT INTO org_nodes (id, parent_id, name, code, node_type, sort_order)
VALUES
  (1, NULL, '工厂',  'FACTORY', 'FACTORY', 0),
  (2, 1,    '产线A', 'LINE-A',  'LINE',    10),
  (3, 1,    '产线B', 'LINE-B',  'LINE',    20),
  (4, 2,    '单元1', 'CELL-1',  'CELL',    10),
  (5, 2,    '单元2', 'CELL-2',  'CELL',    20),
  (6, 3,    '单元3', 'CELL-3',  'CELL',    10)
ON CONFLICT (code) DO NOTHING;

-- Adjust sequences after explicit-id inserts
SELECT setval('org_nodes_id_seq', GREATEST((SELECT MAX(id) FROM org_nodes), 1));

-- ─── org_node_closure (ancestor-descendant pairs) ────────────────────────────
-- Self-rows (depth=0) plus transitive closure for the hierarchy above.

INSERT INTO org_node_closure (ancestor_id, descendant_id, depth)
SELECT a.id, d.id, ABS(a.sort_order - d.sort_order)  -- placeholder; real depth below
FROM org_nodes a, org_nodes d WHERE FALSE;  -- no-op to satisfy linter; real inserts follow

INSERT INTO org_node_closure (ancestor_id, descendant_id, depth) VALUES
  -- FACTORY self + all descendants
  (1, 1, 0),
  (1, 2, 1),
  (1, 3, 1),
  (1, 4, 2),
  (1, 5, 2),
  (1, 6, 2),
  -- LINE-A self + children
  (2, 2, 0),
  (2, 4, 1),
  (2, 5, 1),
  -- LINE-B self + child
  (3, 3, 0),
  (3, 6, 1),
  -- CELLs self-rows
  (4, 4, 0),
  (5, 5, 0),
  (6, 6, 0)
ON CONFLICT (ancestor_id, descendant_id) DO NOTHING;

-- ─── energy_types ─────────────────────────────────────────────────────────────
-- ELEC and WATER are seeded by V1.2.0; GAS is not — add all safely.

INSERT INTO energy_types (code, name, unit, sort_order) VALUES
  ('ELEC',  '电',   'kWh', 10),
  ('WATER', '水',   'm3',  20),
  ('STEAM', '蒸汽', 't',   30),
  ('GAS',   '气',   'm3',  40)
ON CONFLICT (code) DO NOTHING;

-- ─── meters ───────────────────────────────────────────────────────────────────
-- influx_measurement matches the INFLUX_BUCKET measurement name used in the seed script.
-- influx_tag_key = 'meter_code', influx_tag_value = the meter code.

INSERT INTO meters
  (code,  name,           energy_type_id,
   org_node_id,
   influx_measurement, influx_tag_key, influx_tag_value,
   enabled)
SELECT
  m.code,
  m.name,
  et.id,
  n.id,
  'energy_reading',
  'meter_code',
  m.code,
  TRUE
FROM (VALUES
  ('M-1', 'Cell-1 电表',  'ELEC',  'CELL-1'),
  ('M-2', 'Cell-2 电表',  'ELEC',  'CELL-2'),
  ('M-3', 'Cell-1 水表',  'WATER', 'CELL-1'),
  ('M-4', 'Cell-3 水表',  'WATER', 'CELL-3'),
  ('M-5', 'LineA 气表',   'GAS',   'LINE-A'),
  ('M-6', 'LineB 蒸汽表', 'STEAM', 'LINE-B')
) AS m(code, name, et_code, node_code)
JOIN energy_types et ON et.code = m.et_code
JOIN org_nodes    n  ON n.code  = m.node_code
ON CONFLICT (code) DO NOTHING;

COMMIT;
