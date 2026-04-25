# Deployment

## 首次部署

```bash
# 1. 准备环境变量（用强密码）
cp .env.example .env
openssl rand -base64 32         # 生成 EMS_DB_PASSWORD
openssl rand -base64 48         # 生成 EMS_JWT_SECRET
# 编辑 .env 填入

# 2. 构建 & 启动
docker compose build
docker compose up -d

# 3. 验证
curl http://localhost/actuator/health/liveness
```

## 版本升级

```bash
docker compose pull
docker compose up -d
```

## 回滚

修改 `.env` 里 `EMS_VERSION=旧版本号`，然后：

```bash
docker compose down
docker compose up -d
```

## 数据库迁移

Flyway 随应用启动自动执行。破坏性变更禁止（见 spec §11.5）。
