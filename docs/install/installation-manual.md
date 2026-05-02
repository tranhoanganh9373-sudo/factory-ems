# Factory EMS · 安装手册（完整版）

> 适用版本：v1.7.0+ ｜ 最近更新：2026-05-01
> 受众：复杂场景下的实施工程师 / 平台架构师 / SRE
> 阅读时长：按章节挑读；通读约 2 小时

本手册是 [installation-guide.md](./installation-guide.md) 的**兜底参考**——主流程 80% 场景跟着 guide 走就行；遇到这些问题再看本手册：

- 不能用 compose 自带的 Postgres / InfluxDB（公司有 RDS、要外部独立运维）
- 客户公司有边缘 nginx / IIS / F5，要做反代
- 内网无出网（离线部署）
- 高可用 / 多实例 / 集群
- 部署机有 SELinux / firewalld / 强化策略
- 容量规划超过 500 仪表
- 公司要求做日志聚合 / 接入 SIEM

---

## §1 部署模式选型

| 模式 | 适用 | 复杂度 | 维护成本 |
|---|---|---|---|
| **A. 单机 + 内置 DB**（默认）| 50~500 仪表，单一站点 | ⭐ | 低 |
| **B. 单机 + 外部 PostgreSQL** | 公司有统一 DB 平台 / 要 PITR | ⭐⭐ | 中 |
| **C. 单机 + 外部 PG + 外部 InfluxDB** | DB 全部走云托管 | ⭐⭐ | 中 |
| **D. 多实例后端（共享 DB）** | 流量大 / 要无停机更新 | ⭐⭐⭐ | 高 |
| **E. K8s 部署** | 公司全量上 K8s | ⭐⭐⭐⭐ | 高 |
| **F. 离线 / 气隙部署** | 涉密 / 内网无出网 | ⭐⭐⭐ | 中 |

90% 客户走 A 或 B。后续章节按"主路径 = A"展开，B/C/D/F 单独成节。E（K8s）需定制，请联系项目方。

---

## §2 OS 层面准备

### 2.1 时钟同步

EMS 大量依赖时间戳——时序数据、告警状态机、审计日志、电价分时全部基于本机时钟。**部署机时钟漂移 > 5 秒会导致数据错乱**。

```bash
# Ubuntu/Debian
sudo apt install -y chrony
sudo systemctl enable --now chrony

# RHEL/CentOS
sudo dnf install -y chrony
sudo systemctl enable --now chronyd

# 验证
chronyc tracking | grep "Last offset"   # 应在 ms 级别
```

如果是内网无 NTP，向客户要个内部 NTP 服务器地址，配置到 `/etc/chrony/chrony.conf`。

### 2.2 时区

容器默认 UTC。如果业务习惯按本地时区看时间戳（报表、审计、告警），有两种方案：

**方案 A**（推荐）：保留容器内 UTC，让前端按浏览器本地时区渲染。

**方案 B**：在 `docker-compose.yml` 给所有服务加 `TZ`：

```yaml
environment:
  TZ: Asia/Shanghai
```

⚠️ 改 TZ 后，**已有的时序数据时间戳是 UTC 写入**，前后混用会让历史 vs 新数据时区不一致。建议**只在首次部署时定**，后期不要改。

### 2.3 防火墙

#### Ubuntu (ufw)

```bash
sudo ufw allow 22/tcp        # SSH
sudo ufw allow 8888/tcp      # EMS HTTP（如果不接 HTTPS）
sudo ufw allow 443/tcp       # EMS HTTPS（如果接 HTTPS）
sudo ufw enable
```

#### RHEL (firewalld)

```bash
sudo firewall-cmd --permanent --add-port=8888/tcp
sudo firewall-cmd --permanent --add-port=443/tcp
sudo firewall-cmd --reload
```

**不要**对外开放：5432（Postgres）、8086（InfluxDB）、8080（后端直连）、4318（Tempo OTLP）。这些都是内网联通用，对外放等于把 DB 放公网。

### 2.4 SELinux（RHEL / CentOS）

如果 `getenforce` 返回 `Enforcing`，docker bind mount 可能被拒。两个选择：

**A. 给挂载目录打 docker label**（推荐，最小授权）：

```bash
sudo chcon -Rt svirt_sandbox_file_t /opt/factory-ems/data /opt/factory-ems/deploy /opt/factory-ems/logs
```

**B. 在 compose volumes 后加 `:Z` 标志**（让 docker 自动打 label）：

```yaml
volumes:
  - ./data/postgres:/var/lib/postgresql/data:Z
```

**C. 关 SELinux**（不推荐，仅作快速验证）：

```bash
sudo setenforce 0
```

### 2.5 文件描述符上限

高并发时（500+ 仪表 + 大量 WebSocket）默认 `ulimit -n 1024` 可能不够。给 docker 服务调高：

```bash
# /etc/systemd/system/docker.service.d/override.conf
[Service]
LimitNOFILE=65536
```

```bash
sudo systemctl daemon-reload
sudo systemctl restart docker
```

### 2.6 内核参数

PostgreSQL / InfluxDB 受益于以下内核参数（写入 `/etc/sysctl.d/99-ems.conf` 后 `sysctl -p`）：

```ini
vm.max_map_count=262144      # InfluxDB 推荐
vm.swappiness=10             # 降低 swap 倾向，减少 IO 抖动
fs.file-max=1000000
```

---

## §3 用户与目录规划

### 3.1 部署用户

不要用 root 跑日常运维。建一个非特权用户：

```bash
sudo useradd -m -s /bin/bash ems-ops
sudo usermod -aG docker ems-ops    # 让它能跑 docker
```

后续把 `/opt/factory-ems` 所有权改给它：

```bash
sudo chown -R ems-ops:ems-ops /opt/factory-ems
```

### 3.2 推荐目录结构

```
/opt/factory-ems/                    # 安装根
├── .env                             # 凭据，权限 600
├── docker-compose.yml
├── deploy/
│   └── collector.yml                # 采集配置
├── data/                            # 数据持久化（备份目标）
│   ├── postgres/
│   ├── influxdb/
│   ├── ems_uploads/                 # 报表导出 + 平面图
│   └── collector/                   # 断网缓冲 SQLite
├── logs/
│   └── ems/                         # 应用日志
├── nginx/
│   └── ssl/                         # HTTPS 证书（如自己管）
└── scripts/                         # 自定义运维脚本
    ├── backup.sh
    └── healthcheck.sh
```

### 3.3 磁盘规划

如果 `/opt` 跟系统盘共用，建议另挂数据盘到 `/opt/factory-ems/data`：

```bash
# 假设 /dev/sdb 是数据盘
sudo mkfs.ext4 /dev/sdb
sudo mkdir -p /opt/factory-ems/data
sudo mount /dev/sdb /opt/factory-ems/data
echo "/dev/sdb /opt/factory-ems/data ext4 defaults,noatime 0 2" | sudo tee -a /etc/fstab
```

`noatime` 减少 InfluxDB 写入时的元数据更新开销。

---

## §4 容量规划

### 4.1 规模 vs 资源

| 仪表数 | 写入量（条/天）| CPU | 内存 | 磁盘（年增长）|
|---|---|---|---|---|
| 50 | 1 万 | 2 核 | 4 GB | 10 GB |
| 200 | 5 万 | 2 核 | 6 GB | 30 GB |
| 500 | 15 万 | 4 核 | 8 GB | 80 GB |
| 1000 | 30 万 | 8 核 | 16 GB | 150 GB |
| 5000 | 150 万 | 16 核 | 32 GB | 800 GB（建议外部 InfluxDB）|

**写入量估算公式**：仪表数 × 24 小时 × (3600 / 轮询间隔秒)。轮询间隔 5 秒、500 仪表 = 500 × 24 × 720 ≈ 860 万条/天 → 用 5000 规模配置。

### 4.2 InfluxDB 保留策略

默认无限保留——长期会撑爆磁盘。建议设置：

```bash
docker exec factory-ems-influxdb-1 \
  influx bucket update --name factory_ems --retention 730d   # 保留 2 年原始
```

> 时序数据被 rollup 5min/15min/1h/1d/1mo 各级压缩，长跨度查询走聚合层不查原始；老原始数据 2 年后清理对业务无影响。

### 4.3 PostgreSQL 调优

500 仪表 + 中等并发用户场景下，HikariCP 默认 30 连接够用。规模上去后调：

`application-prod.yml`（或通过 env 覆盖）：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 60
      connection-timeout: 10000
      idle-timeout: 600000
```

PostgreSQL 服务端配置（小规模 8GB 内存场景）：

```ini
# postgres 容器的 postgresql.conf 注入（compose volumes 加挂载）
shared_buffers = 2GB
effective_cache_size = 6GB
maintenance_work_mem = 256MB
work_mem = 16MB
max_connections = 200
random_page_cost = 1.1     # SSD
```

---

## §5 模式 B：外部 PostgreSQL

### 5.1 准备 DB

请客户 DBA 提前建库：

```sql
CREATE DATABASE factory_ems;
CREATE USER ems WITH PASSWORD '<生成的强密码>';
GRANT ALL PRIVILEGES ON DATABASE factory_ems TO ems;
\c factory_ems
GRANT ALL ON SCHEMA public TO ems;
```

要求 PostgreSQL **15+**（用了较新 SQL 特性）。RDS / Aurora / Cloud SQL 等托管也行，确认版本。

### 5.2 改 compose

把 `postgres` 服务从 `docker-compose.yml` 删除（或注释），改 `factory-ems` 服务的 depends_on：

```yaml
factory-ems:
  # 删除 postgres depends_on
  depends_on:
    influxdb:
      condition: service_healthy
```

### 5.3 改 .env

```bash
EMS_DB_HOST=db.client.internal      # 外部 DB 地址
EMS_DB_NAME=factory_ems
EMS_DB_USER=ems
EMS_DB_PASSWORD=<DBA 给的密码>
```

### 5.4 验证

```bash
docker compose up -d factory-ems
docker compose logs factory-ems | grep -i "DataSource initialized\|Successfully validated\|Migrated"
```

应看到 Flyway 把所有迁移跑完。如果连接失败，进 `factory-ems` 容器测：

```bash
docker compose exec factory-ems sh -c \
  "apk add postgresql-client && pg_isready -h \$EMS_DB_HOST -U \$EMS_DB_USER"
```

---

## §6 模式 C：外部 InfluxDB

类似 §5：

1. 让 InfluxDB 管理员创建 org、bucket、admin token
2. 删 compose 里的 `influxdb` 服务
3. `.env` 改：

```bash
EMS_INFLUX_URL=https://influx.client.internal:8086
EMS_INFLUX_TOKEN=<拿到的 token>
EMS_INFLUX_ORG=<拿到的 org>
EMS_INFLUX_BUCKET=<拿到的 bucket>
```

4. 同时要把 `factory-ems` 服务的 `EMS_INFLUX_URL` 显式 env 删掉（compose 默认硬编码 `http://influxdb:8086`，会覆盖 .env）。

---

## §7 HTTPS 完整方案

### 7.1 方案 B：本机 nginx + Let's Encrypt（自动续期）

#### 第 1 步：装 certbot

```bash
sudo apt install -y certbot python3-certbot-nginx     # Debian/Ubuntu
sudo dnf install -y certbot python3-certbot-nginx     # RHEL/CentOS
```

#### 第 2 步：临时关掉 docker nginx，腾出 80 端口

```bash
docker compose stop nginx
```

#### 第 3 步：申请证书

```bash
sudo certbot certonly --standalone -d ems.client.com
# 证书会落到 /etc/letsencrypt/live/ems.client.com/{fullchain.pem,privkey.pem}
```

#### 第 4 步：把证书挂到 docker nginx

修改 `docker-compose.yml`：

```yaml
nginx:
  image: nginx:alpine
  ports:
    - "80:80"
    - "443:443"
  volumes:
    - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
    - ./nginx/conf.d:/etc/nginx/conf.d:ro
    - frontend_dist:/usr/share/nginx/html:ro
    - ./data/ems_uploads:/var/www/uploads:ro
    - /etc/letsencrypt:/etc/letsencrypt:ro      # 新增
```

修改 `nginx/conf.d/default.conf`，加 443 server + 80 → 443 跳转。模板见 `docs/ops/nginx-setup.md`。

#### 第 5 步：续期自动化

```bash
# /etc/cron.d/certbot-renew
0 3 * * * root certbot renew --quiet --post-hook "docker compose -f /opt/factory-ems/docker-compose.yml restart nginx"
```

### 7.2 方案 A：反代到企业 nginx / IIS / F5

让前端反代设备做：

- 监听 443
- 终止 SSL（证书在反代设备）
- 代理 `/` → `<EMS 部署机>:8888`
- 透传 `Host`、`X-Real-IP`、`X-Forwarded-For`、`X-Forwarded-Proto`

nginx 反代示例：

```nginx
location / {
    proxy_pass http://<ems-ip>:8888;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_read_timeout 300s;
}
```

EMS 自身的 nginx 还是监听 8888，反代到达后由它分发到前端 / 后端。**不要**让企业反代直接到 :8080（后端），会绕过前端静态资源服务。

### 7.3 mTLS（仅高安全场景）

OPC UA 客户端证书加固见 `docs/ops/opcua-cert-management.md`。

EMS 平台 UI 接客户端证书 mTLS 在反代层做（在企业 nginx 配 `ssl_verify_client on`），EMS 本身不做 mTLS 校验。

---

## §8 备份与恢复

### 8.1 完整备份脚本

```bash
#!/bin/bash
# /opt/factory-ems/scripts/backup.sh
set -euo pipefail

BACKUP_ROOT=/opt/factory-ems-backups
DATE=$(date +%Y%m%d-%H%M%S)
DEST="$BACKUP_ROOT/$DATE"
mkdir -p "$DEST"

cd /opt/factory-ems

# 1. .env（一次性归档到密码管理器，这里只快照防丢）
cp .env "$DEST/env.snapshot"
chmod 600 "$DEST/env.snapshot"

# 2. PostgreSQL 逻辑备份
docker compose exec -T postgres \
  pg_dump -U "$(grep EMS_DB_USER .env | cut -d= -f2)" \
          -d "$(grep EMS_DB_NAME .env | cut -d= -f2)" \
  | gzip > "$DEST/postgres.sql.gz"

# 3. InfluxDB（用 backup 子命令，热备）
docker compose exec -T influxdb \
  influx backup /tmp/influx-backup \
    --token "$(grep EMS_INFLUX_TOKEN .env | cut -d= -f2)"
docker cp factory-ems-influxdb-1:/tmp/influx-backup "$DEST/influx-backup"

# 4. 上传文件（rsync 增量）
rsync -a --delete data/ems_uploads/ "$DEST/ems_uploads/"

# 5. 清理 30 天前的备份
find "$BACKUP_ROOT" -maxdepth 1 -type d -mtime +30 -exec rm -rf {} +

echo "Backup done: $DEST"
```

### 8.2 cron 定时

```bash
# /etc/cron.d/factory-ems-backup
# m h dom mon dow user command
0 2 * * * ems-ops /opt/factory-ems/scripts/backup.sh >> /var/log/ems-backup.log 2>&1
```

### 8.3 3-2-1 策略

行业惯例：

- **3 份**副本（含原件）
- **2 种**介质（本地磁盘 + 远端对象存储 / 磁带）
- **1 份**异地（远端机房 / 云）

最小可执行版：本地 cron 每日备份 + `rclone` 推到 S3 / OSS / 对象存储：

```bash
# scripts/backup.sh 末尾追加
rclone sync "$DEST" remote:factory-ems-backups/$DATE \
  --include "*.gz" --include "ems_uploads/**" --include "influx-backup/**"
```

### 8.4 恢复演练

```bash
#!/bin/bash
# scripts/restore.sh — 在测试机验证备份
SOURCE=/path/to/backup-YYYYMMDD-HHMMSS
TARGET=/opt/factory-ems-restore

# 准备目标目录
git clone <repo> "$TARGET"
cp "$SOURCE/env.snapshot" "$TARGET/.env"

cd "$TARGET"
docker compose up -d postgres influxdb
sleep 10

# 恢复 PostgreSQL
zcat "$SOURCE/postgres.sql.gz" | docker compose exec -T postgres \
  psql -U "$(grep EMS_DB_USER .env | cut -d= -f2)" \
       -d "$(grep EMS_DB_NAME .env | cut -d= -f2)"

# 恢复 InfluxDB
docker cp "$SOURCE/influx-backup" factory-ems-influxdb-1:/tmp/influx-restore
docker compose exec -T influxdb influx restore /tmp/influx-restore --full \
  --token "$(grep EMS_INFLUX_TOKEN .env | cut -d= -f2)"

# 恢复上传文件
rsync -a "$SOURCE/ems_uploads/" data/ems_uploads/

# 起栈
docker compose up -d
```

跑通一遍 = 备份可用。**部署后 30 天内必做一次**。

---

## §9 高可用（多实例）

### 9.1 后端多实例

EMS 后端**支持**多实例水平扩展（v1.7+），需注意：

- **DB 必须共享**：所有实例连同一个 PostgreSQL（走模式 B / C）
- **InfluxDB 共享**：同上
- **采集层不能并行**：v1.7 的 collector 还是单实例设计——多实例会重复采、重复写。
  解决：只在**一个实例**上 `EMS_COLLECTOR_ENABLED=true`，其他实例置 false。
- **会话 / 缓存**：JWT 是无状态的，多实例无 sticky session 需求；本地缓存（如 Caffeine）每实例独立，差异在秒级，可接受。

### 9.2 负载均衡

前面用 nginx upstream / HAProxy / 云负载均衡：

```nginx
upstream ems_backend {
    server ems-1:8888;
    server ems-2:8888;
    keepalive 32;
}

server {
    listen 443 ssl;
    location / {
        proxy_pass http://ems_backend;
    }
}
```

健康检查走 `/actuator/health/readiness`。

### 9.3 PostgreSQL 主从

走云托管自带（RDS Multi-AZ / Aurora）或自建 streaming replication，本手册不展开。EMS 应用层面只看一个连接串，所有 HA 由 DB 层透明。

### 9.4 InfluxDB 副本

单机版 InfluxDB OSS 不支持原生副本。要副本要么用 **InfluxDB Enterprise**，要么用对象存储级备份（每小时全量 dump 到 S3）。

---

## §10 离线 / 气隙部署

### 10.1 准备离线介质

在外网机器上：

```bash
# 1. 拉镜像
docker pull factory-ems:2.3.1
docker pull factory-ems-frontend:2.3.1
docker pull postgres:15-alpine
docker pull influxdb:2.7-alpine
docker pull nginx:alpine

# 2. 打包
docker save factory-ems:2.3.1 \
            factory-ems-frontend:2.3.1 \
            postgres:15-alpine \
            influxdb:2.7-alpine \
            nginx:alpine \
            -o factory-ems-images.tar

# 3. 拉源码
git clone <repo> factory-ems
cd factory-ems

# 4. 一起打包
cd ..
tar czf factory-ems-offline.tgz factory-ems factory-ems-images.tar
```

### 10.2 内网导入

```bash
tar xzf factory-ems-offline.tgz
cd factory-ems

# 导入镜像
docker load -i ../factory-ems-images.tar

# 改 .env，按 §2 启动
docker compose up -d
```

### 10.3 升级

后续有新版本，在外网机重新打包镜像 → 介质拷贝 → 内网 `docker load` + `docker compose up -d`。

---

## §11 监控接入

### 11.1 Prometheus 拉取

EMS 默认在 `/actuator/prometheus` 暴露 metrics（端口 8080，仅集群内）。Prometheus 拉取：

```yaml
# prometheus.yml
scrape_configs:
  - job_name: factory-ems
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['factory-ems:8080']
```

如果 Prometheus 在 docker compose 之外，需要：
- 让 EMS 暴露 8080 给 Prometheus 所在网段
- 或把 Prometheus 加进同一个 compose

### 11.2 Grafana 看板

直接用 ops/observability/grafana/dashboards/ 里 7 个预设 JSON 文件，导入 Grafana。详见 `docs/product/observability-dashboards-guide.md`。

### 11.3 告警接入

Prometheus AlertManager 配置进 `ops/observability/prometheus/alerts.yml`，16 条规则覆盖 4 个 SLO。详见 `docs/product/observability-slo-rules.md`。

---

## §12 日志聚合

### 12.1 默认（容器日志）

```bash
docker compose logs factory-ems --tail 100 --follow
```

适合调试。生产长期保留要走聚合方案。

### 12.2 接 Loki（推荐，配合 Grafana）

观测栈起栈后，Loki 自动收集 `/var/lib/docker/containers/*/*.log`。Grafana 里按容器名查询：

```logql
{container_name="factory-ems-factory-ems-1"} |~ "ERROR|WARN"
```

### 12.3 接 ELK / 公司 SIEM

通过 `fluentd` / `filebeat` agent 读 docker log，转 syslog 或 ELK。

docker compose 服务级别可换 log driver：

```yaml
factory-ems:
  logging:
    driver: syslog
    options:
      syslog-address: "udp://siem.client.internal:514"
      tag: "factory-ems"
```

---

## §13 安全加固

### 13.1 OS 层

- [ ] 关掉 root SSH 登录（`PermitRootLogin no`）
- [ ] SSH 用密钥而非密码
- [ ] `fail2ban` 防爆破
- [ ] 系统补丁定期 `apt upgrade` / `dnf upgrade`
- [ ] `unattended-upgrades` 自动安全更新

### 13.2 Docker 层

- [ ] Docker daemon 不监听 TCP（默认就是 unix socket，不要打开 `tcp://0.0.0.0:2375`）
- [ ] 容器以非 root 运行（EMS 镜像默认 `ems` 用户）
- [ ] 不挂载 `/var/run/docker.sock` 进容器
- [ ] 镜像只从可信仓库拉取
- [ ] 定期跑 `docker scout` / `trivy` 扫漏洞

### 13.3 应用层

- [ ] `.env` 权限 `600`
- [ ] HTTPS 终止
- [ ] 强制 admin 改默认密码
- [ ] 备份 ADMIN 凭据进密码管理器（不要邮件 / 微信）
- [ ] 审计日志保留 ≥ 1 年（合规要求）
- [ ] Webhook 全部用 HMAC-SHA256 签名（默认开启）

### 13.4 网络层

- [ ] 8086 / 5432 / 8080 / 4318 不对外暴露
- [ ] 反代加 rate limit（防 brute force 登录）
- [ ] WAF（如有 F5 / Cloudflare）至少开 OWASP top 10 规则

---

## §14 客户交付物清单

部署完成后给客户的最小交付包：

```
factory-ems-handover-YYYYMMDD/
├── 访问指南.pdf              # 含 URL、初始密码、注意事项
├── 用户手册.pdf              # 由 docs/product/user-guide.md 转 PDF
├── 应急联系.txt              # 项目方电话 / 邮箱 / 远程支持入口
├── 备份与恢复 SOP.pdf        # 由本手册 §8 转 PDF + 客户专属信息
├── 凭据归档（密码管理器导出）  # 加密 zip
└── 验收清单.xlsx             # 客户签字确认
```

PDF 渲染 markdown 用 [pandoc](https://pandoc.org/) 或 mdpdf 都行。

---

## §15 常见问题（高级）

### Q1：可不可以同时跑两个不同版本做 A/B？

**A**：不可以。两个版本连同一个 DB 会冲突（Flyway 版本号一致性、schema 兼容性）。要 A/B 必须各自独立的 DB（再做数据复制）。

### Q2：能不能 read-replica 让客户只读访问？

**A**：可以。PG 起从库（`pg_basebackup` + streaming replication），起一个新的 EMS 后端实例 + DB_HOST 指向从库。但 EMS 应用本身不区分读写——直接连从库会让所有写操作失败。需要在反代层强行禁用所有 `POST/PUT/DELETE`，相当于做一个"只读快照"。

### Q3：RDS 没法暴露 superuser，Flyway 报权限错？

**A**：让 DBA 给 `ems` 用户加上 `CREATE EXTENSION` 权限，或在客户那边由 superuser 预创建需要的 extension（`pg_trgm`、`pgcrypto` 等），EMS Flyway 不会再创建。

### Q4：InfluxDB 1.x 能用吗？

**A**：不能。EMS 用 InfluxDB 2.x 的 Flux 语法和 token 鉴权。1.x 升级到 2.x 见 InfluxDB 官方迁移指南。

### Q5：能不能用 Postgres 13 / 14？

**A**：不推荐。代码里用了 PG 15 才稳定的若干 SQL 特性。13 / 14 部分场景能跑但风险自担，正式生产请用 15+。

### Q6：能不能跑在 ARM 服务器上？

**A**：能。EMS 镜像是 multi-arch，docker 会自动选 arm64。性能与 x86 同档大致持平。

### Q7：可以反代到子路径吗（如 `https://client.com/ems/...`）？

**A**：当前版本前端 / API 都 hard-code 在根路径 `/`。反代到子路径需要改前端 vite.config.ts 的 `base` + nginx rewrite，未在主线支持。请走子域名（`ems.client.com`）方案。

---

## §16 升级矩阵

| 从 → 到 | 直接升级? | 说明 |
|---|---|---|
| 1.5.x → 1.6.x | ✅ | 引入告警，新表 |
| 1.6.x → 1.7.x | ✅ | 引入 observability，新指标 |
| 1.7.x → 2.0.x | ✅ | 引入 cost，新表 |
| 2.0.x → 2.1.x | ✅ | 引入 billing，新表 |
| 2.1.x → 2.2.x | ✅ | 告警增强 |
| 2.2.x → 2.3.x | ✅ | collector 协议层重构（兼容现有配置）|
| 1.x → 2.0+ | ⚠️ 跨大版本，建议先升到 1.7.x，再升到 2.0.x | |

升级时先备份 → 看 release notes → 再升级。

---

**相关文档**

- 安装向导（80% 主流程）：[installation-guide.md](./installation-guide.md)
- 快速试跑：[quickstart.md](./quickstart.md)
- 环境变量参考：[../config/environment-variables.md](../config/environment-variables.md)
- 部署运维：[../ops/deployment.md](../ops/deployment.md)
- nginx 配置：[../ops/nginx-setup.md](../ops/nginx-setup.md)
- 观测栈部署：[../ops/observability-deployment.md](../ops/observability-deployment.md)
- OPC UA 证书：[../ops/opcua-cert-management.md](../ops/opcua-cert-management.md)
- 各模块 runbook：[../ops/](../ops/)
- 备份脚本（§8.1 实装）：`scripts/backup.sh`
- 恢复脚本（§8.4 实装）：`scripts/restore.sh`

**装好之后按顺序上线（"装-通-看-警-钱-报-效"）**

1. 选型：[meter-selection-guide.md](./meter-selection-guide.md)
2. 现场施工：[field-installation-sop.md](./field-installation-sop.md)
3. 通道导入：`scripts/csv-to-channels.py` + `scripts/import-channels.sh`
4. 仪表导入：`scripts/csv-to-meters.py` + `scripts/import-meters.sh`（或前端 `/meters` 页"批量导入"按钮，v2 新增）
5. 看板上线：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
6. 5 分钟演示：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
7. 告警上线：[alarm-commissioning-sop.md](./alarm-commissioning-sop.md)
8. 账单上线：[billing-commissioning-sop.md](./billing-commissioning-sop.md)
9. 月报自动化：[report-automation-sop.md](./report-automation-sop.md)
10. 生产能效：[production-energy-sop.md](./production-energy-sop.md)

**装好之后做什么**

- 平台总览（功能 + 用户）：[../product/README.md](../product/README.md)
- 各模块功能概览：见 `../product/*-feature-overview.md`
