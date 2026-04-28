# Verification · 装机交付包（无硬件等待期）

**Date:** 2026-04-28
**Trigger:** 子项目 1.5 v1.5.0 发布后的"等硬件期间"准备工作
**Baseline:** v1.5.0（145 测试）

---

## §1 范围

子项目 1.5 v1.5.0 发布后到第一个真实客户部署前的**装机交付包**。无代码逻辑改动，只
加部署模板 / 现场工具 / 文档：

- `deploy/collector.yml` (默认 stub) + `deploy/collector.yml.example`（含 TCP / RTU 多场景）
- `docker-compose.yml` 补 collector 配置外挂 + buffer 持久化 volume
- `.env.example` 补 collector 开关
- `scripts/collector-preflight.sh` — 现场一键体检（磁盘 / 端口 / YAML / 设备连通）
- `docs/ops/onboarding-checklist.md` — 客户对接信息收集表
- `docs/ops/dry-run-procedure.md` — 无硬件演练步骤
- `tools/modbus-simulator` — j2mod TCP slave 本地模拟器（新模块）

## §2 文件 inventory

| 文件 | 状态 | 验证方式 |
|---|---|---|
| `deploy/collector.yml` | 新增（committed stub） | bind-mount 默认安全，enabled=false |
| `deploy/collector.yml.example` | 新增 | 含 TCP 单仪表 / TCP 多 unit-id / RTU 三组示例 |
| `docker-compose.yml` | 改 | 加 `/etc/ems/collector.yml` 挂载 + `/data/collector` volume |
| `.env.example` | 改 | 加 `EMS_COLLECTOR_ENABLED` / `EMS_COLLECTOR_CONFIG` |
| `scripts/collector-preflight.sh` | 新增 | bash -n 通过；本地 dry run 输出 OK/WARN/FAIL 表 |
| `docs/ops/onboarding-checklist.md` | 新增 | — |
| `docs/ops/dry-run-procedure.md` | 新增 | — |
| `tools/modbus-simulator/**` | 新增（Maven 模块） | `mvn compile` 通过；监听端口 5502 实证可达 |
| `pom.xml` | 改 | 注册新模块 |

## §3 验收

### §3.1 Modbus 模拟器联通性

```text
[sim] Modbus TCP slave listening on 0.0.0.0:5502 unit=1
[sim] registers: 0x2000=voltage_a F32, 0x2014=power_active F32 (W), 0x4000=energy_total U32 (×0.01 kWh)
[sim] press Ctrl-C to stop
--- TCP probe 5502 ---
OK port 5502 reachable
```

### §3.2 Preflight 脚本 dry-run

`scripts/collector-preflight.sh --config deploy/collector.yml --env .env.example` 输出：

```
── 1) 磁盘 & 目录 ──
[ OK ] 目录可写: ./data
[ OK ] 目录可写: ./logs
[ OK ] 目录可写: ./data/collector
[ OK ] 目录可写: ./data/ems_uploads
[ OK ] 可用空间 460148 MB ≥ 5 GB

── 2) .env ──
[ OK ] .env 存在: .env.example
[WARN] EMS_DB_PASSWORD 看起来还是占位符 — 上线前必须改
[WARN] EMS_JWT_SECRET 看起来还是占位符 — 上线前必须改
[WARN] EMS_INFLUX_TOKEN 看起来还是占位符 — 上线前必须改
[WARN] INFLUXDB_ADMIN_PASSWORD 看起来还是占位符 — 上线前必须改

── 3) 依赖端口 ──
       PostgreSQL 主机 postgres 解析不到 — 在 docker compose exec factory-ems 里跑
       InfluxDB 主机 influxdb 解析不到 — 在 docker compose exec factory-ems 里跑

── 4) collector.yml ──
[WARN] python3 未装 — 跳过 YAML 语法校验

── 5) 设备连通性 ──    （devices=[] 跳过）
── 6) Modbus 直读 ──   （mbpoll 未装跳过）

══════════════════════════════════════════════════════
  总计: OK 6 / WARN 5 / FAIL 0
══════════════════════════════════════════════════════
有警告 — 评估后可继续。  (exit 1)
```

预期：默认状态全 PASS / 仅 WARN（占位符警告 + 工具缺失警告）；FAIL=0。✅

### §3.3 ems-collector 测试矩阵 (v1.5.0 baseline)

`mvn -pl ems-collector,tools/modbus-simulator -am test` — 145 测试全绿（v1.5.0 基线无回归），simulator 模块编译通过。

> 详细 IT（CollectorEndToEndIT）已在 v1.5.1 / v1.5.2 / v1.5.3 三个 verification log
> 中重复验证：fake j2mod slave + 真 CollectorService 全链路解码 / 状态机 / metrics
> 全部 PASS。本次只新增 simulator main()，不动业务代码逻辑。

### §3.4 docker-compose.yml 改动语义检查

- `volumes` 加：
  - `./deploy/collector.yml:/etc/ems/collector.yml:ro` — bind-mount 现场配置
  - `./data/collector:/data/collector` — SQLite buffer 持久化（容器重建不丢数据）
- `environment` 加：
  - `EMS_COLLECTOR_BUFFER_PATH=/data/collector/buffer.db`
  - `SPRING_CONFIG_ADDITIONAL_LOCATION=${EMS_COLLECTOR_CONFIG:-optional:file:/etc/ems/collector.yml}`

`optional:` 前缀保证默认模板（enabled=false）下文件丢失也不 fail-fast；现场启用时
`.env` 改成 `EMS_COLLECTOR_CONFIG=file:/etc/ems/collector.yml`（无 optional:）即可硬性
要求文件存在。

## §4 给现场工程师的 quick start

1. `cp .env.example .env`，改占位符密码
2. （可选）按 `docs/ops/onboarding-checklist.md` 跟客户对完仪表清单，写 `deploy/collector.yml`
3. `EMS_COLLECTOR_ENABLED=false ./scripts/collector-preflight.sh` 看是否全 PASS
4. `docker compose up -d`
5. 现场没硬件想演练的，按 `docs/ops/dry-run-procedure.md` 起 simulator
6. 真上线前再 `EMS_COLLECTOR_ENABLED=true` 启 collector，跑 §11.3 装机 SOP

## §5 已知不接（继续推后）

- 下一个真实客户部署还没有；preflight + onboarding 表必须经过一次现场反哺修订（runbook §11.3 第三阶段已写明）
- mbpoll 自动安装：依赖 OS 包管理器，preflight 仅做 detect，留给现场工程师
- 配置 CRUD UI（前端编辑 collector.yml）— 推 Plan 1.6
- TLS 仍仅 docs

## §6 下一步

- 等到第一个真实客户场点：跑 onboarding-checklist → preflight → docker compose up → §11.3 SOP
- 反哺：把现场踩坑写到 `docs/ops/verification-YYYY-MM-DD-real-hardware.md`，更新 runbook §11
