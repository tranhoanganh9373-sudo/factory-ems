# Dev Setup

## Prerequisites
- JDK 21, Maven 3.9+ (or wrapper)
- Node 20+, pnpm 9+
- Docker Desktop 24+

## 启动

```bash
# 1. 启 Postgres
docker compose -f docker-compose.dev.yml up -d

# 2. 后端
./mvnw -pl ems-app spring-boot:run

# 3. 前端（另一终端）
cd frontend && pnpm install && pnpm dev
```

访问 http://localhost:5173，用 admin / admin123! 登录。
