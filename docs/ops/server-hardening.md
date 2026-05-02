# 服务器加固清单（合规场景进阶）

> **适用版本**：v1.0.0-ga ｜ 最近更新：2026-05-01
> **受众**：实施工程师 / 客户 SRE / 等保测评对接人
> **适用边界**：PCI-DSS、等保 2.0 三级、ISO 27001 等需要"主机加固报告"的合规交付。常规内网部署不用全部执行；**`installation-manual.md §3.1` 已覆盖最低基线**（非 root 用户 + docker 组 + 目录所有权），本文补充进阶项。

---

## §1 加固清单总览

| 项 | 目的 | 等保 2.0 对应控制项 | 工时 |
|---|---|---|---|
| §2 部署用户最小权限 sudoers | 限定可执行的命令范围 | 8.1.4.1 a) | 15 min |
| §3 SSH 加固（密钥 + 禁密码） | 防暴力破解 / 撞库 | 8.1.4.1 b) | 20 min |
| §4 文件权限审计 | 防越权读取凭据 | 8.1.4.4 | 10 min |
| §5 auditd 关键操作审计 | 留痕、可追溯 | 8.1.4.3 | 30 min |
| §6 系统包更新策略 | 补丁管理 | 8.1.4.5 | 15 min |
| §7 防火墙 / 端口最小化 | 攻击面控制 | 8.1.4.1 c) | 10 min |
| §8 容器安全 | 越狱风险 | 8.1.4.6 | 15 min |
| §9 加固验证脚本 | 一键自检 | — | 5 min |

完整跑完约 2 小时；客户验收前一周排上即可。

---

## §2 部署用户最小权限 sudoers

`installation-manual.md §3.1` 建用户后默认加 docker 组就能全控 docker，但等保审计要求"sudo 命令逐条记录 + 不能 root shell"。改为 NOPASSWD 列白名单：

```bash
# /etc/sudoers.d/ems-ops
# 仅允许 ems 用户执行有限的 docker / systemctl 命令，无密码
ems ALL=(root) NOPASSWD: /usr/bin/docker compose -f /opt/factory-ems/docker-compose.yml *
ems ALL=(root) NOPASSWD: /usr/bin/docker logs *
ems ALL=(root) NOPASSWD: /usr/bin/docker ps
ems ALL=(root) NOPASSWD: /usr/bin/docker exec factory-ems-postgres-1 *
ems ALL=(root) NOPASSWD: /usr/bin/docker exec factory-ems-influxdb-1 *
ems ALL=(root) NOPASSWD: /bin/systemctl restart factory-ems
ems ALL=(root) NOPASSWD: /bin/systemctl status factory-ems
ems ALL=(root) NOPASSWD: /opt/factory-ems/scripts/backup.sh
ems ALL=(root) NOPASSWD: /opt/factory-ems/scripts/restore.sh

# 显式拒绝 root shell
ems ALL=(ALL) !/bin/bash, !/bin/sh, !/usr/bin/su, !/bin/su
```

```bash
sudo visudo -cf /etc/sudoers.d/ems-ops   # 必须先校验语法
sudo chmod 0440 /etc/sudoers.d/ems-ops
```

> **同时把 `ems` 从 `docker` 组移除**——否则上面 sudoers 形同虚设：
> `sudo gpasswd -d ems docker`

---

## §3 SSH 加固（密钥 + 禁密码）

### §3.1 用密钥替代密码

```bash
# 在运维笔记本生成
ssh-keygen -t ed25519 -C "ems-ops@$(hostname)" -f ~/.ssh/ems_ed25519

# 上传公钥到服务器
ssh-copy-id -i ~/.ssh/ems_ed25519.pub ems@<host>

# 验证免密码登录
ssh -i ~/.ssh/ems_ed25519 ems@<host>
```

### §3.2 禁用密码登录

```bash
# /etc/ssh/sshd_config.d/99-ems-hardening.conf
PasswordAuthentication no
PermitRootLogin no
ChallengeResponseAuthentication no
PubkeyAuthentication yes
MaxAuthTries 3
LoginGraceTime 30
ClientAliveInterval 300
ClientAliveCountMax 2
AllowUsers ems
Protocol 2
```

```bash
sudo sshd -t                      # 校验配置
sudo systemctl reload sshd
```

> ⚠️ 改之前**保持当前 SSH 会话别断**，新开一个会话验证密钥能登录后再退出原会话；改错了会被锁在外面。

### §3.3 fail2ban（可选）

防御 SSH 暴力扫描：

```bash
sudo apt install fail2ban
# /etc/fail2ban/jail.d/sshd.conf
[sshd]
enabled = true
maxretry = 3
findtime = 600
bantime = 3600
sudo systemctl enable --now fail2ban
```

---

## §4 文件权限审计

EMS 关键路径权限基线：

| 路径 | 所有者 | 权限 | 说明 |
|---|---|---|---|
| `/opt/factory-ems/` | `ems:ems` | `0750` | 部署根目录 |
| `/opt/factory-ems/.env` | `ems:ems` | `0600` | 含数据库密码 / JWT secret，**严禁** 0644 |
| `/opt/factory-ems/data/` | `ems:ems` | `0750` | 容器数据卷挂载根 |
| `/opt/factory-ems/data/secrets/` | `ems:ems` | `0700` | OPC UA / MQTT 凭据 |
| `/opt/factory-ems/data/secrets/*.pfx` | `ems:ems` | `0600` | 客户端证书私钥 |
| `/opt/factory-ems-backups/` | `ems:ems` | `0700` | 备份不能给其他用户读 |
| `/var/log/ems-backup.log` | `ems:adm` | `0640` | adm 组可读，方便日志收集 |

**一键自检**：

```bash
# scripts/check-perms.sh
set -e
fail=0
check() {
  local path=$1
  local owner=$2
  local mode=$3
  local actual_owner actual_mode
  actual_owner=$(stat -c '%U:%G' "$path")
  actual_mode=$(stat -c '%a' "$path")
  if [ "$actual_owner" != "$owner" ] || [ "$actual_mode" != "$mode" ]; then
    echo "FAIL $path (got $actual_owner $actual_mode, want $owner $mode)"
    fail=1
  fi
}

check /opt/factory-ems            ems:ems  750
check /opt/factory-ems/.env       ems:ems  600
check /opt/factory-ems/data       ems:ems  750
check /opt/factory-ems/data/secrets ems:ems 700
check /opt/factory-ems-backups    ems:ems  700

[ $fail -eq 0 ] && echo "OK"
exit $fail
```

测评前每台服务器跑一次，结果存档备查。

---

## §5 auditd 关键操作审计

等保 8.1.4.3 要求"对重要用户行为进行审计"。auditd 比 syslog 颗粒度细：

```bash
sudo apt install auditd audispd-plugins  # Debian/Ubuntu
# 或
sudo dnf install audit                   # RHEL/CentOS

sudo systemctl enable --now auditd
```

**EMS 专用规则**：

```ini
# /etc/audit/rules.d/ems.rules

# 1. 监视 .env 改动（含数据库密码）
-w /opt/factory-ems/.env -p wa -k ems_env_change

# 2. 监视 secrets 目录
-w /opt/factory-ems/data/secrets/ -p wa -k ems_secret_change

# 3. 监视 sudoers
-w /etc/sudoers -p wa -k sudoers_change
-w /etc/sudoers.d/ -p wa -k sudoers_change

# 4. 监视 docker 命令调用（含参数）
-a always,exit -F arch=b64 -S execve -F path=/usr/bin/docker -k docker_exec

# 5. SSH 登录失败
-w /var/log/auth.log -p wa -k ssh_auth     # Debian
# -w /var/log/secure -p wa -k ssh_auth     # RHEL

# 6. 提权（uid 变化）
-a always,exit -F arch=b64 -S setuid -S setgid -k privilege_change
```

```bash
sudo augenrules --load
sudo systemctl restart auditd
sudo auditctl -l   # 验证已生效
```

**查日志**：

```bash
sudo ausearch -k ems_env_change --start today
sudo ausearch -k docker_exec --start today | aureport -x --summary
```

**接入企业 SIEM**：用 `audisp-remote` 转发到 syslog server / Splunk / Loki。

---

## §6 系统包更新策略

```bash
# Debian / Ubuntu — 自动安装安全补丁，但不重启
sudo apt install unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades

# RHEL / CentOS / AlmaLinux
sudo dnf install dnf-automatic
sudo systemctl enable --now dnf-automatic.timer
```

**EMS 容器侧**：

- `factory-ems` / `nginx` 等镜像由 CI 构建发布；客户侧 `docker compose pull && docker compose up -d` 滚动升级。
- `postgres:15` / `influxdb:2.7` 基础镜像在 `docker-compose.yml` 钉死 minor 版本（如 `15.6`），由实施侧定期手动验证升级，不开自动 latest 拉取（避免不兼容变更）。

---

## §7 防火墙 / 端口最小化

```bash
# 仅放行 SSH + HTTPS（其他端口靠 docker 内部网络互通，不暴露主机）
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp comment 'SSH'
sudo ufw allow 443/tcp comment 'HTTPS'
# 如还需 HTTP→HTTPS 跳转：
sudo ufw allow 80/tcp comment 'HTTP redirect'
sudo ufw enable
sudo ufw status verbose
```

> ⚠️ **不要直接暴露 5432 / 8086 / 8080**：PostgreSQL / InfluxDB / Spring Boot 端口只在 docker 网络互通，不要在主机层 `-p 5432:5432`。检查 `docker-compose.yml` 中 `ports:` 段，只有 `nginx` 容器需要 `:80` 和 `:443`。

---

## §8 容器安全

```yaml
# docker-compose.override.yml — 加固增量
services:
  factory-ems:
    user: "1000:1000"           # 显式非 root
    read_only: true             # 根文件系统只读
    tmpfs:
      - /tmp:size=64m
    cap_drop: [ALL]             # 丢掉所有 Linux capabilities
    cap_add: [NET_BIND_SERVICE] # 仅在确实绑定 <1024 端口时加；EMS 监 8080 不需要
    security_opt:
      - no-new-privileges:true
    pids_limit: 200
    mem_limit: 2g
    cpus: 1.5

  postgres:
    user: "999:999"             # postgres 镜像默认 999
    cap_drop: [ALL]
    cap_add: [CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID]   # PG 启动需要
    security_opt:
      - no-new-privileges:true

  influxdb:
    cap_drop: [ALL]
    security_opt:
      - no-new-privileges:true
```

**镜像扫描**（在 CI 或本机）：

```bash
# Trivy 扫已发布镜像
trivy image --severity HIGH,CRITICAL factory-ems:1.0.0-ga
# Docker scout（Docker Desktop 自带）
docker scout cves factory-ems:1.0.0-ga
```

CRITICAL 漏洞交付前必须修复，或在文档里写清缓解措施。

---

## §9 加固验证脚本

落到 `scripts/audit-hardening.sh`：

```bash
#!/usr/bin/env bash
set -e
echo "=== Factory EMS Hardening Audit ==="

# 1. 用户与权限
id ems | grep -q docker && echo "FAIL: ems still in docker group" || echo "OK: ems not in docker group"
[ -f /etc/sudoers.d/ems-ops ] && echo "OK: ems-ops sudoers present" || echo "FAIL: missing /etc/sudoers.d/ems-ops"

# 2. SSH
sudo sshd -T 2>/dev/null | grep -E '^(passwordauthentication|permitrootlogin)' \
  | awk '{ print "  ", $1, $2 }'

# 3. 文件权限
bash /opt/factory-ems/scripts/check-perms.sh

# 4. auditd
systemctl is-active --quiet auditd && echo "OK: auditd running" || echo "FAIL: auditd not running"
sudo auditctl -l | grep -q ems_env_change && echo "OK: ems audit rules loaded" \
  || echo "FAIL: ems audit rules missing"

# 5. 防火墙
sudo ufw status | grep -q "Status: active" && echo "OK: ufw active" || echo "FAIL: ufw inactive"
sudo ufw status | grep -q "5432" && echo "FAIL: PostgreSQL port exposed!" || echo "OK: 5432 not exposed"

# 6. 容器
docker inspect factory-ems-app-1 --format '{{.HostConfig.ReadonlyRootfs}}' \
  | grep -q true && echo "OK: app container read-only" || echo "WARN: app container writable"
docker inspect factory-ems-postgres-1 --format '{{.HostConfig.SecurityOpt}}' \
  | grep -q no-new-privileges && echo "OK: pg no-new-privileges" \
  || echo "WARN: pg without no-new-privileges"

echo "=== done ==="
```

```bash
chmod +x scripts/audit-hardening.sh
sudo bash scripts/audit-hardening.sh > hardening-report-$(date +%F).txt
```

输出存档备查。

---

## §10 已知妥协与例外

部分加固项与 EMS 功能冲突，需在合规报告里写"例外说明"：

- **容器 read_only + InfluxDB**：InfluxDB 要写 `/var/lib/influxdb2`（数据卷）和 `/tmp`，已用 `volumes` + `tmpfs` 处理；如客户测评工具误判，提供 `docker inspect` 输出说明。
- **postgres 容器需 SETUID/SETGID capability**：PG 启动时切换到 `postgres` 用户用得上，没法 cap_drop 全部。
- **OPC UA `.pfx` 文件 0600**：当前由 `FilesystemSecretResolver` 读，0600 已是最严；不能再收紧到 0400，因为更新证书要写。
- **EMS 业务用户密码哈希存 PostgreSQL**：bcrypt-12 是行业常用强度，不再加 KMS 包封；客户要求时可对接 Vault Transit（首版未规划，按定制处理）。

---

## §11 相关文档

- 安装手册（含基础 OS 准备）：[../install/installation-manual.md](../install/installation-manual.md)
- 备份与恢复：[`installation-manual.md §8`](../install/installation-manual.md)
- nginx / HTTPS 配置：[nginx-setup.md](./nginx-setup.md)
- 部署运维：[deployment.md](./deployment.md)
- 审计日志（应用层）：[../product/auth-audit-feature-overview.md](../product/auth-audit-feature-overview.md)
- 实施交付清单：[onboarding-checklist.md](./onboarding-checklist.md)
