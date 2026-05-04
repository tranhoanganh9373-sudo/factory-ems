# Factory EMS · 安装向导（生产部署）

> 适用版本：v1.7.0+ ｜ 最近更新：2026-05-01
> 受众：负责把 Factory EMS 装到客户现场或公司机房的实施工程师
> 阅读时长：完整跑一遍约 60 分钟（不含等待 Docker 拉镜像）

本向导是主流程版，覆盖 80% 客户场景。遇到这里没列的可选项（高可用、外部 DB、内网代理、PKI 证书等），请进 [installation-manual.md](./installation-manual.md)。

---

## §1 部署前 checklist

部署前先把以下清单走一遍，避免到现场才发现卡点：

### 1.1 硬件 / OS

| 项目 | 最低 | 推荐 |
|---|---|---|
| CPU | 2 核 | 4 核 |
| 内存 | 4 GB | 8 GB |
| 磁盘 | 50 GB SSD | 200 GB SSD |
| OS | Ubuntu 20.04+ / CentOS 8+ / Debian 11+ / RHEL 9+ | Ubuntu 22.04 LTS |
| 时区 | UTC（推荐）| 业务时区（如 Asia/Shanghai）|

500 仪表 / 10 万条/天 这一规模用推荐配置。规模更大请走 [installation-manual.md §6 容量规划](./installation-manual.md)。

### 1.2 软件

```bash
# Docker Engine 24+
docker --version

# Docker Compose v2（不是 v1 的 docker-compose）
docker compose version

# git（拉代码用）
git --version
```

如果三个都返回版本号，过。否则按官方文档装齐。

### 1.3 网络

- **出网**（部署期）：能访问镜像仓库（Docker Hub 或公司私有镜像源）和 git 仓库
- **入网**（运行期）：客户内网能访问部署机的 **8888**（HTTP）或 **443**（HTTPS）端口
- **内部端口**：同一台机部署不需要开 5432 / 8086 / 8080 给外网（compose 内部互联）

### 1.4 准备凭据

部署前，准备好这些东西（拿不到部分就先到现场再补）：

- [ ] 仓库 git 地址 + 访问权限
- [ ] 客户访问的 hostname（例如 `ems.client.com` 或纯 IP）
- [ ] 是否要 HTTPS（生产环境强烈建议要）
- [ ] 可用端口（默认 8888 是否被占）
- [ ] 现场仪表 / PLC 地址清单（部署后再录入）

---

## §2 拉代码 + 准备 .env

### 2.1 拉代码

```bash
git clone <仓库地址> /opt/factory-ems
cd /opt/factory-ems
```

`/opt/factory-ems` 仅为推荐路径——任意路径都行，但**不要放在 `/tmp`**（重启后丢数据）。

### 2.2 生成 .env

```bash
cp .env.example .env
chmod 600 .env
```

用以下命令一次性生成必填的 4 个强密码 / 密钥：

```bash
echo "EMS_DB_PASSWORD=$(openssl rand -base64 32)"
echo "EMS_JWT_SECRET=$(openssl rand -base64 48)"
echo "EMS_INFLUX_TOKEN=$(openssl rand -hex 32)"
echo "INFLUXDB_ADMIN_PASSWORD=$(openssl rand -base64 32)"
```

把输出完整粘贴到 `.env` 里覆盖原占位值。

### 2.3 选定 EMS_VERSION

生产环境禁止使用 `1.1.0-SNAPSHOT`（默认值是开发用）。改成最新稳定版 tag：

```bash
# .env 里改
EMS_VERSION=2.3.1   # 替换成实际可用的 tag
```

可用 tag 列表见仓库的 release 页面或 CHANGELOG。

### 2.4 校验 .env

跑一遍这个脚本，避免占位符漏掉：

```bash
required=(EMS_DB_PASSWORD EMS_JWT_SECRET EMS_INFLUX_TOKEN INFLUXDB_ADMIN_PASSWORD)
for var in "${required[@]}"; do
  val=$(grep -E "^$var=" .env | cut -d= -f2-)
  if [ -z "$val" ] || [[ "$val" == *change_me* ]] || [ ${#val} -lt 16 ]; then
    echo "FAIL: $var is missing / placeholder / too short"
  fi
done
echo "校验完成"
```

`FAIL` 行说明对应变量没设好。完整变量参考 [`../config/environment-variables.md`](../config/environment-variables.md)。

---

## §3 起栈

### 3.1 拉 / 构建镜像

```bash
docker compose pull          # 拉远端镜像（如果有私有仓库）
# 或
docker compose build         # 本地从源码构建（首次 5~10 分钟）
```

### 3.2 启动

```bash
docker compose up -d
```

`-d` 后台运行。首次启动 Postgres 和 InfluxDB 会做 init，约 30 秒；Spring Boot 起栈约 10 秒。

### 3.3 等待健康

```bash
watch -n 2 'docker compose ps'
```

直到所有服务都 `Up (healthy)`：

```
factory-ems-factory-ems-1   Up X seconds (healthy)
factory-ems-postgres-1      Up X seconds (healthy)
factory-ems-influxdb-1      Up X seconds (healthy)
factory-ems-nginx-1         Up X seconds
```

如果 `factory-ems` 卡在 `health: starting`，看日志定位：

```bash
docker compose logs factory-ems --tail 100
```

常见原因见 §10 排错。

---

## §4 验收

按顺序跑这 5 个验收命令，全过即部署成功：

### 4.1 后端 Liveness

```bash
curl -fsS http://localhost:8888/actuator/health/liveness
```

期望：`{"status":"UP"}`

### 4.2 后端 Readiness

```bash
curl -fsS http://localhost:8888/actuator/health/readiness
```

期望：`{"status":"UP"}`

### 4.3 前端入口

```bash
curl -fsS -o /dev/null -w "%{http_code}\n" http://localhost:8888/
```

期望：`200`

### 4.4 登录接口

```bash
curl -fsS -X POST http://localhost:8888/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123!"}' | head -c 200
```

期望返回 JSON 含 `accessToken` 字段。

### 4.5 浏览器登录

打开 `http://<部署机 IP>:8888` 或 `http://localhost:8888`，用 `admin` / `admin123!` 登录，看到仪表盘即验收通过。

---

## §5 首次登录与初始化

### 5.1 立刻改 admin 密码

进 `/profile`，改成强密码（至少 12 字符 + 大小写 + 数字 + 符号）。

记下来放密码管理器，平台不开放邮件 / 短信自助找回。

### 5.2 建组织树

进 `/orgtree`，根据客户实际组织建一棵树：

```
客户公司（根）
├── 主厂区
│   ├── 一车间
│   └── 二车间
└── 分厂区
```

每个节点填上面积、人数等元数据（后续分摊会用）。

### 5.3 建用户和角色

进 `/admin/users`，按客户实际人员配置：

- 一个 ADMIN（备份管理员，丢失 admin 时救场）
- 1~3 个 OPERATOR（运维 / 值班）
- 1~2 个 FINANCE（财务）
- N 个 VIEWER（按需）

每个非 ADMIN 用户必须绑定节点权限范围，否则看不到任何数据。

### 5.4 登记仪表

进 `/meters`，把现场仪表清单录入。基本属性 + 采集协议 + 阈值。

### 5.5 启用采集

#### 写采集配置

```bash
cp deploy/collector.yml.example deploy/collector.yml
nano deploy/collector.yml
```

按现场 PLC / 网关填好通道 + 设备点位映射。详见 [`../product/collector-protocols-user-guide.md`](../product/collector-protocols-user-guide.md)。

#### 启用

`.env` 里改：

```bash
EMS_COLLECTOR_ENABLED=true
EMS_COLLECTOR_CONFIG=file:/etc/ems/collector.yml
```

注意去掉 `optional:` 前缀，让配置文件丢失时启动失败而不是静默降级。

重启：

```bash
docker compose up -d
```

### 5.6 验证采集

进 `/collector`，看通道是否在线、设备最近一次成功时间是否在更新。

进 `/dashboard`，看 KPI 是否开始有数据。

---

## §6 接 HTTPS（强烈推荐）

⚠️ 公网暴露的 EMS 不上 HTTPS，JWT token 就在传输层裸奔，攻击者抓包就能拿到管理员凭据。

### 6.1 方案 A：反代到现有 HTTPS（推荐）

如果客户已有边缘 nginx / IIS / F5 等反代设备，让它把 `https://ems.client.com/` 反代到部署机的 `:8888`。

这是最省事的方案，证书让客户的反代去管。

### 6.2 方案 B：本机 nginx + Let's Encrypt

适合无现成反代的小型部署。

```bash
# 装 certbot
sudo apt install certbot python3-certbot-nginx     # Ubuntu/Debian

# 申请证书（先关 docker compose 起的 nginx 占用 80 端口，或改证书申请方式）
sudo certbot certonly --standalone -d ems.client.com

# 改 docker-compose.yml 的 nginx 段，加 443 端口和证书挂载
# 详见 ../ops/nginx-setup.md
```

详细步骤进 [installation-manual.md §8 HTTPS](./installation-manual.md)。

### 6.3 方案 C：自签证书（仅内网测试）

```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout ./nginx/ssl/server.key -out ./nginx/ssl/server.crt \
  -subj "/CN=ems.client.local"
```

⚠️ 自签证书浏览器会显示"不安全"，用户每次都要点确认。仅用于内网测试，正式生产用方案 A 或 B。

---

## §7 备份

### 7.1 必备份的 4 样东西

| 内容 | 路径 | 频率 | 大小估计 |
|---|---|---|---|
| `.env` 文件 | `/opt/factory-ems/.env` | 一次性，进密码管理器 | < 1 KB |
| Postgres 数据 | `./data/postgres/` | 每天 | 1~10 GB |
| InfluxDB 数据 | `./data/influxdb/` | 每周 | 5~50 GB |
| 上传文件 | `./data/ems_uploads/` | 每天 | 1~5 GB |

### 7.2 自动化备份脚本（cron）

```bash
# /etc/cron.d/factory-ems-backup
0 2 * * * root /opt/factory-ems/scripts/backup.sh >> /var/log/ems-backup.log 2>&1
```

`scripts/backup.sh` 模板进 [installation-manual.md §10 备份](./installation-manual.md)。

### 7.3 演练恢复

部署完 30 天内至少做一次完整恢复演练：把备份在另一台机器恢复，看能不能正常起栈、登录、查历史数据。没演练的备份等于没有。

---

## §8 升级

### 8.1 升级步骤

```bash
# 1. 备份（重要！）
./scripts/backup.sh

# 2. 拉新 tag
git pull origin main

# 3. 改 .env 里的 EMS_VERSION
nano .env
# EMS_VERSION=2.3.2

# 4. 拉镜像 + 重启
docker compose pull
docker compose up -d

# 5. 看日志确认 Flyway 迁移成功
docker compose logs factory-ems | grep -i flyway
```

### 8.2 回滚步骤

```bash
# 改 .env 里的 EMS_VERSION 回旧版
nano .env
# EMS_VERSION=2.3.1

docker compose down
docker compose up -d
```

⚠️ DB 迁移不可逆。回滚版本后 schema 仍是新版，应用代码是旧版。如果新版引入了破坏性 schema 变更，回滚后老代码会读不出数据。所以升级前必须备份，并且看 release notes 是否有破坏性变更。

---

## §9 监控（可选但推荐）

可观测性栈（Prometheus + Grafana + Loki + Tempo）独立部署：

```bash
# 在 ops/observability/ 目录下
docker compose -f docker-compose.observability.yml up -d
```

详见 [`../ops/observability-deployment.md`](../ops/observability-deployment.md) 和 [`../product/observability-feature-overview.md`](../product/observability-feature-overview.md)。

不上观测栈也能用，只是出问题时排查只能靠 `docker compose logs`，效率低。

---

## §10 排错

### 10.1 起不来

| 现象 | 排查 | 处置 |
|---|---|---|
| `factory-ems` 反复重启 | `docker compose logs factory-ems --tail 50` | 看日志最后一段错误 |
| 看到 `Connection refused: postgres:5432` | postgres 没起 / 密码错 | `docker compose ps`、复查 `.env` |
| 看到 `JWT secret too short` | `EMS_JWT_SECRET` 不到 32 字节 | 重新生成 |
| 看到 `Flyway migration failed` | DB schema 与版本不匹配 | 看具体迁移失败的 SQL，可能是手工改过 DB |
| 看到 `Port 8888 already in use` | 8888 被其他进程占 | `lsof -i :8888` 看是谁；改 compose 里的 host 端口 |

### 10.2 起来了但登录失败

| 现象 | 排查 | 处置 |
|---|---|---|
| 登录返回 401 + admin/admin123! | DB 种子未跑或被覆盖 | `docker compose logs factory-ems \| grep -i "AdminInitializer\|admin\|seed"` |
| 502 Bad Gateway | nginx 找不到后端 | nginx 重启：`docker compose restart nginx`；常见于 `up -d --build` 后后端 IP 变了 |
| 浏览器空白页 | 前端构建产物没挂上 | `docker compose ps frontend-builder` 看是否成功；它跑完 `frontend_dist` 卷里才有内容 |
| Console 报 CORS / 403 | 前端 / API 域名不一致 | 应都走 nginx 8888，避免直连 8080 |

### 10.3 跑起来了但数据不动

| 现象 | 排查 | 处置 |
|---|---|---|
| `/dashboard` 一直空 | 采集没启 | `.env` 里 `EMS_COLLECTOR_ENABLED=true` 了吗？ |
| `/collector` 看到通道全 OFFLINE | 现场网络 / PLC 配置错 | 看 `docker compose logs factory-ems \| grep -i collector`，找具体协议错误 |
| 报表生成卡住 | rollup 任务失败堆积 | 进 `/admin/audit` 或观测看板查 rollup 失败窗口 |

### 10.4 还是不行

把以下信息收集打包发给项目方：

```bash
# 1. 系统信息
uname -a > debug.txt
docker --version >> debug.txt
docker compose version >> debug.txt

# 2. 容器状态（含 health）
docker compose ps >> debug.txt
docker inspect factory-ems-factory-ems-1 | grep -A 10 Health >> debug.txt

# 3. 各服务日志（最近 200 行）
docker compose logs --tail 200 > debug.log

# 4. .env（脱敏后！）
sed 's/=.*/=<redacted>/' .env > env.sanitized

# 打包
tar czf debug-$(date +%Y%m%d).tgz debug.txt debug.log env.sanitized
```

⚠️ 上传 / 邮件之前必须确认 `env.sanitized` 把所有密码 / token 都脱敏了。

---

## §11 部署完成 checklist

最后过一遍：

- [ ] `.env` 文件权限 `600`，所有 `change_me_*` 占位都换了
- [ ] `EMS_VERSION` 不是 `1.1.0-SNAPSHOT`
- [ ] 5 步验收（§4）全过
- [ ] admin 默认密码已改
- [ ] 至少 1 个备份 ADMIN 用户已建
- [ ] 组织树 / 仪表 / 用户 / 权限初始化完成
- [ ] 采集启用（`EMS_COLLECTOR_ENABLED=true` + 配置文件 fail-fast）
- [ ] HTTPS 接入（生产环境必做）
- [ ] 备份脚本配置 + cron 安排
- [ ] 至少做过 1 次恢复演练（部署后 30 天内）
- [ ] 客户已收到部署交付物：访问地址、ADMIN 凭据、文档链接

完成后进 [`../product/user-guide.md`](../product/user-guide.md) 给客户做培训。

---

**相关文档**

- 快速试跑：[quickstart.md](./quickstart.md)
- 完整安装手册（含可选项）：[installation-manual.md](./installation-manual.md)
- 环境变量参考：[../config/environment-variables.md](../config/environment-variables.md)
- 部署运维：[../ops/deployment.md](../ops/deployment.md)
- nginx 配置：[../ops/nginx-setup.md](../ops/nginx-setup.md)
- 观测栈部署：[../ops/observability-deployment.md](../ops/observability-deployment.md)
- 备份脚本：`scripts/backup.sh`
- 恢复脚本：`scripts/restore.sh`

**装好之后按顺序上线（"装-通-看-警-钱-报-效"）**

1. 选型：[meter-selection-guide.md](./meter-selection-guide.md)
2. 现场施工：[field-installation-sop.md](./field-installation-sop.md)
3. 通道导入：`scripts/csv-to-channels.py` + `scripts/import-channels.sh`
4. 仪表导入：`scripts/csv-to-meters.py` + `scripts/import-meters.sh`（或前端 `/meters` 页"批量导入"按钮，v2 新增）
5. 看板上线：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
6. 5 分钟演示：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
7. 报警上线：[alarm-commissioning-sop.md](./alarm-commissioning-sop.md)
8. 账单上线：[billing-commissioning-sop.md](./billing-commissioning-sop.md)
9. 月报自动化：[report-automation-sop.md](./report-automation-sop.md)
10. 生产能效：[production-energy-sop.md](./production-energy-sop.md)

**装好之后做什么**

- 平台总览（功能 + 用户）：[../product/README.md](../product/README.md)
- 用户使用手册：[../product/user-guide.md](../product/user-guide.md)
- 各模块功能概览：见 `../product/*-feature-overview.md`（仪表 / 电价 / 报表 / 成本 / 账单 / 报警 等）
