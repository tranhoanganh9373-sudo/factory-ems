-- ems-app/src/main/resources/db/migration/V0.0.0__placeholder.sql
-- 占位迁移，确保 Flyway 初始化。真实迁移从 V1.0.0 开始。
CREATE TABLE IF NOT EXISTS ems_boot_marker (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    booted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT single_row CHECK (id = 1)
);
INSERT INTO ems_boot_marker (id) VALUES (1) ON CONFLICT DO NOTHING;
