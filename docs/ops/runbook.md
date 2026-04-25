# Runbook — 常见故障排查

## 症状：Spring Boot 启动失败，日志 "Failed to configure DataSource"

检查 `postgres` 容器 `docker compose ps`；`EMS_DB_PASSWORD` 与 Postgres 容器环境变量是否一致。

## 症状：登录返回 "用户名或密码错误"

1. 确认 admin 存在：`docker exec -it postgres psql -U ems factory_ems -c 'select username from users;'`
2. 若 admin 缺失：重新跑 Flyway 或手动插入（参考 `V1.0.8__seed_reference_data.sql`）

## 症状：401 Authentication fails 持续

JWT secret 变更会导致已有 access token 全部失效。让用户重新登录即可；refresh token 同样失效。

## 重置某用户密码

```sql
-- 在应用内用 admin 调接口最方便：
-- PUT /api/v1/users/{id}/password/reset  {"newPassword":"..."}
-- 也可以直接 SQL（不推荐）
```

## 如何看审计日志

- 应用内：`/admin/audit`（ADMIN）
- 直查库：`SELECT * FROM audit_logs ORDER BY occurred_at DESC LIMIT 100;`
