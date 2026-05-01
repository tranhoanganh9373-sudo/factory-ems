-- 同步 admin 用户的 BCrypt hash 到 V1.0.8 的预期值（明文仍为 'admin123!'）
-- 历史 live DB 中的 hash 因测试覆盖发生漂移，此迁移把 hash 重置到一份新生成的 BCrypt 12-round 值，
-- 并解锁账号、清零失败次数。明文密码不变。
UPDATE users
SET password_hash = '$2a$12$pnxwW1juI6NnE0QqGnMEaOdMZ8BXNanWS7K6TWquB3PtGoJHGsjAG',
    failed_attempts = 0,
    locked_until = NULL
WHERE username = 'admin';
