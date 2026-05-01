# Factory EMS · 环境变量参考

> 适用版本：v1.7.0+ ｜ 最近更新：2026-05-01
> 受众：实施工程师 / 运维 / 平台管理员
> 配套文件：[`.env.example`](../../.env.example) / [`docker-compose.yml`](../../docker-compose.yml)

本文档列出 Factory EMS 部署需要的**全部**环境变量。所有变量通过 `.env` 文件传给 `docker compose`，或通过容器编排平台的 secret / configmap 注入。

**敏感等级速查**：
- 🔴 **必填且必须强密码**（部署前必须自定义，不可用默认值）
- 🟡 **建议自定义**（默认值能跑，但生产环境推荐改）
- 🟢 **保留默认即可**（除非有特殊场景）

---

## §1 必填变量速查

复制下面这一段到 `.env`，替换占位为强随机值，平台就能起：

```bash
# 必填 — 部署前必须自定义
EMS_DB_PASSWORD=<openssl rand -base64 32>
EMS_JWT_SECRET=<openssl rand -base64 48>
EMS_INFLUX_TOKEN=<openssl rand -hex 32>
INFLUXDB_ADMIN_PASSWORD=<openssl rand -base64 32>
```

其余变量都有合理默认值。完整清单见后续章节。

---

## §2 数据库（PostgreSQL）

| 变量 | 类型 | 默认值 | 等级 | 说明 |
|---|---|---|---|---|
| `EMS_DB_HOST` | string | `postgres` | 🟢 | DB 主机名。compose 内是 service name；独立 DB 部署填 IP 或 DNS |
| `EMS_DB_NAME` | string | `factory_ems` | 🟢 | DB 库名。Flyway 会在此库内建所有表 |
| `EMS_DB_USER` | string | `ems` | 🟡 | DB 用户名。生产建议改成非默认值 |
| `EMS_DB_PASSWORD` | string | 无 | 🔴 | DB 密码。**必填**，建议 32 字节以上 |

**生成示例**：

```bash
EMS_DB_PASSWORD=$(openssl rand -base64 32)
```

**注意**：
- 改 `EMS_DB_USER` 后第一次启动会自动用此用户名建库；中途改密码需要先在 DB 端 `ALTER USER ... PASSWORD ...` 同步。
- compose 默认让 `postgres` 容器自带这个 DB（`POSTGRES_DB=$EMS_DB_NAME`）；如果用外部 DB，需提前手工建库 + grant 权限。

---

## §3 认证（JWT）

| 变量 | 类型 | 默认值 | 等级 | 说明 |
|---|---|---|---|---|
| `EMS_JWT_SECRET` | string ≥32B | 无（dev profile 有内置默认）| 🔴 | 用于签发 JWT 的密钥。**必填生产环境**；至少 32 字节随机 |

**生成示例**：

```bash
EMS_JWT_SECRET=$(openssl rand -base64 48)
```

**坑点**：
- dev profile 有内置默认值（仅供本地开发），生产**绝对不要复用**
- 修改 `EMS_JWT_SECRET` 会让所有现有 token 立即失效，所有用户被踢下线
- 不建议轮换（轮换需配合用户登出 + 重新登录）

---

## §4 时序数据库（InfluxDB）

| 变量 | 类型 | 默认值 | 等级 | 说明 |
|---|---|---|---|---|
| `EMS_INFLUX_URL` | URL | `http://influxdb:8086` | 🟢 | InfluxDB 地址。compose 内置不需要改 |
| `EMS_INFLUX_TOKEN` | string ≥32B | 无 | 🔴 | InfluxDB API Token。**必填**，至少 32 字符 |
| `EMS_INFLUX_ORG` | string | `factory` | 🟢 | InfluxDB org 名 |
| `EMS_INFLUX_BUCKET` | string | `factory_ems` | 🟢 | InfluxDB bucket 名 |
| `EMS_INFLUX_MEASUREMENT` | string | `energy_reading` | 🟢 | 时序写入的 measurement 名 |
| `INFLUXDB_ADMIN_USER` | string | `influx_admin` | 🟡 | InfluxDB UI 管理员用户名 |
| `INFLUXDB_ADMIN_PASSWORD` | string ≥8B | 无 | 🔴 | InfluxDB UI 管理员密码。**必填** |

**生成示例**：

```bash
EMS_INFLUX_TOKEN=$(openssl rand -hex 32)
INFLUXDB_ADMIN_PASSWORD=$(openssl rand -base64 32)
```

**坑点**：
- `EMS_INFLUX_TOKEN` 同时被两端使用：InfluxDB 容器初始化时把它注册为 admin token，应用启动时用它读写数据。两边必须一致。
- 改 `EMS_INFLUX_BUCKET` 等于换 bucket，**老数据不会自动迁移**——只有持续写入的新数据进新 bucket。
- 如果要直接用 InfluxDB UI 调试，把 `docker-compose.yml` 里 InfluxDB 的 `ports` 注释解开，访问 `http://localhost:8086`。

---

## §5 采集（Collector）

| 变量 | 类型 | 默认值 | 等级 | 说明 |
|---|---|---|---|---|
| `EMS_COLLECTOR_ENABLED` | `true` / `false` | `false` | 🟡 | 是否启动采集线程。首次部署默认关；现场对完仪表后改 true |
| `EMS_COLLECTOR_CONFIG` | Spring location | `optional:file:/etc/ems/collector.yml` | 🟡 | 采集配置文件路径（容器内）。前缀 `optional:` = 文件不存在时跳过 |
| `EMS_COLLECTOR_BUFFER_PATH` | path | `/data/collector/buffer.db` | 🟢 | 断网缓冲 SQLite 文件路径。compose 默认挂到主机 `./data/collector/` |

**生产推荐**：现场把 `deploy/collector.yml` 写好后，把 `EMS_COLLECTOR_CONFIG` 改成不带 `optional:` 前缀：

```bash
EMS_COLLECTOR_CONFIG=file:/etc/ems/collector.yml
```

这样配置文件丢失会**立即启动失败**（fail-fast），避免静默回退到空 device 列表导致采集"在跑但没数据"。

详见 [`../product/collector-protocols-user-guide.md`](../product/collector-protocols-user-guide.md)。

---

## §6 文件存储

| 变量 | 类型 | 默认值 | 等级 | 说明 |
|---|---|---|---|---|
| `EMS_REPORT_EXPORT_BASE_DIR` | path | `/data/ems_uploads` | 🟢 | 报表导出 Excel/CSV 的落盘目录（容器内）|
| `EMS_FLOORPLAN_BASE_DIR` | path | `/data/ems_uploads/floorplans` | 🟢 | 平面图底图 PNG/JPG 的落盘目录（容器内）|

**坑点**：
- compose 把 `./data/ems_uploads` 挂载到 `/data/ems_uploads`，主机侧目录权限要让容器内 `ems` 用户可写（首次起栈 docker 会自动创建）
- nginx 通过只读卷把这个目录挂到 `/var/www/uploads`，前端可直接预览图片
- 如果换路径，需要同时改 compose 的 `volumes` 段，否则容器内空目录无写权限会导致**上传 400 错误**

---

## §7 可观测性

| 变量 | 类型 | 默认值 | 等级 | 说明 |
|---|---|---|---|---|
| `OTLP_TRACING_ENDPOINT` | URL | `http://tempo:4318/v1/traces` | 🟢 | Trace 上报 OTLP 端点。可观测性栈起后默认走 tempo |
| `HOSTNAME` | string | Docker 自动注入 | 🟢 | metrics 标签的 instance 维度 |

**关闭 trace 上报**：把 `OTLP_TRACING_ENDPOINT` 设为不可达地址，或在 `application-prod.yml` 把 `management.tracing.sampling.probability` 调到 `0`。

详见 [`../product/observability-config-reference.md`](../product/observability-config-reference.md)。

---

## §8 镜像与 profile

| 变量 | 类型 | 默认值 | 等级 | 说明 |
|---|---|---|---|---|
| `EMS_VERSION` | semver tag | `1.1.0-SNAPSHOT` | 🟡 | 后端 / 前端镜像 tag。生产建议钉死稳定版本（如 `2.3.1`）|
| `SPRING_PROFILES_ACTIVE` | csv | `dev`（在 .env.example 中） | 🟡 | 激活的 Spring profile。compose 内显式覆盖为 `prod` |

**升级流程**：

```bash
# 改 .env 里的 EMS_VERSION=2.3.1
docker compose pull
docker compose up -d
```

**回滚流程**：

```bash
# 改 .env 里的 EMS_VERSION=2.3.0
docker compose down
docker compose up -d
```

---

## §9 .env.example 完整模板

```bash
# .env.example — copy to .env and customize (NEVER commit .env)
EMS_VERSION=1.1.0-SNAPSHOT
SPRING_PROFILES_ACTIVE=prod

# Database
EMS_DB_HOST=postgres
EMS_DB_NAME=factory_ems
EMS_DB_USER=ems
EMS_DB_PASSWORD=change_me_strong_password

# JWT
EMS_JWT_SECRET=change_me_at_least_32_bytes_long_random

# InfluxDB
EMS_INFLUX_TOKEN=please-change-me-32+chars-long-token
EMS_INFLUX_ORG=factory
EMS_INFLUX_BUCKET=factory_ems
EMS_INFLUX_MEASUREMENT=energy_reading
INFLUXDB_ADMIN_USER=influx_admin
INFLUXDB_ADMIN_PASSWORD=change_me_strong_password

# Collector
EMS_COLLECTOR_ENABLED=false
EMS_COLLECTOR_CONFIG=optional:file:/etc/ems/collector.yml
```

实际部署时请把所有 `change_me_*` 占位替换。

---

## §10 安全审查清单

部署前过一遍：

- [ ] `EMS_DB_PASSWORD` 改成 32 字节以上随机值
- [ ] `EMS_JWT_SECRET` 改成 48 字节以上随机值
- [ ] `EMS_INFLUX_TOKEN` 改成 32 字符以上随机值
- [ ] `INFLUXDB_ADMIN_PASSWORD` 改成 32 字节以上随机值
- [ ] `.env` 文件**没有**被 commit 到 git（`.gitignore` 已包含）
- [ ] `.env` 文件权限 = `600`（仅 owner 可读）
- [ ] 主机不开放 8086（InfluxDB）/ 5432（Postgres）端口到公网
- [ ] 8888 端口建议反代到 443（HTTPS）后对外暴露
- [ ] 备份 `.env` 到密码管理器或安全归档（密钥丢了 token / 数据全失联）

**自动检查脚本**（可放进 CI）：

```bash
#!/bin/bash
set -e
required=(EMS_DB_PASSWORD EMS_JWT_SECRET EMS_INFLUX_TOKEN INFLUXDB_ADMIN_PASSWORD)
for var in "${required[@]}"; do
  val=$(grep -E "^$var=" .env | cut -d= -f2-)
  if [ -z "$val" ] || [[ "$val" == *change_me* ]] || [ ${#val} -lt 16 ]; then
    echo "FAIL: $var is missing / placeholder / too short"
    exit 1
  fi
done
echo "OK: all required secrets set"
```

---

## §11 进阶变量（仅特殊场景）

下列变量平时不需要碰，列在这里方便排错时定位：

| 变量 | 来源 | 何时可能要改 |
|---|---|---|
| `JAVA_OPTS` | 容器内 ENTRYPOINT | 调 JVM 内存（`-Xmx2g` 等）|
| `LANG` / `TZ` | OS 默认 | 容器内时区不对（默认 UTC，业务上海时区可在 compose 加 `TZ=Asia/Shanghai`）|
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | application-prod.yml | DB 连接池大小（默认 30，巨型部署调大）|

**JVM 调优示例**（在 compose 的 environment 段加）：

```yaml
JAVA_OPTS: "-Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"
```

---

## §12 已知坑

- **首次起栈失败 + 改 .env 后再起还是用旧密码**：postgres / influxdb 容器**首次启动**才会注入这些密码到内部 DB，之后改 .env 不生效。完整重置：`docker compose down -v` 删卷再起。生产环境改密码请走 DB / Influx 自身的命令（`ALTER USER` / `influx user password`）。
- **`SPRING_PROFILES_ACTIVE` 在 .env 设了但好像没用**：`docker-compose.yml` 在 `factory-ems` 服务的 `environment:` 段显式写了 `SPRING_PROFILES_ACTIVE: prod`，会**覆盖** .env 的设置。这是有意为之——compose 部署强制走 prod profile。
- **`EMS_INFLUX_URL` 在 .env 改了但应用还是连 compose 内的 influxdb**：同上，`docker-compose.yml` 显式写了 `EMS_INFLUX_URL: http://influxdb:8086`。要外部 InfluxDB 必须改 compose 文件本身。

---

**相关文档**

- 安装向导：[../install/installation-guide.md](../install/installation-guide.md)
- 安装手册（完整版）：[../install/installation-manual.md](../install/installation-manual.md)
- 快速试跑：[../install/quickstart.md](../install/quickstart.md)
- 部署运维：[../ops/deployment.md](../ops/deployment.md)
