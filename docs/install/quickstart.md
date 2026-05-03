# Factory EMS · 快速试跑（5 分钟）

> 适用版本：v1.7.0+ ｜ 最近更新：2026-05-01
> 受众：评估者 / 想 5 分钟看一眼系统长什么样的人
> 不适合：正式生产部署（请用 [installation-guide.md](./installation-guide.md)）

---

## §1 前置条件

只需要一台装了 **Docker Desktop**（或 Docker Engine + Compose v2）的机器：

- macOS / Windows：装 Docker Desktop（最新版）
- Linux：装 Docker Engine 24+ 和 Docker Compose v2

至少 **4 GB 可用内存**、**10 GB 可用磁盘**。

---

## §2 三步起栈

```bash
# 1. 拉代码
git clone <仓库地址> factory-ems
cd factory-ems

# 2. 准备一份默认 .env（弱密码，仅本地试跑）
cp .env.example .env

# 3. 起栈
docker compose up -d
```

等约 1~2 分钟（首次构建镜像），用以下命令确认全部 healthy：

```bash
docker compose ps
```

应该看到 `factory-ems`、`postgres`、`influxdb`、`nginx` 都在 `Up (healthy)` 状态。

---

## §3 登录看一眼

浏览器打开 http://localhost:8888，用以下凭据登录：

- **用户名**：`admin`
- **密码**：`admin123!`

进入仪表盘（首页）。试跑环境刚起时没有任何采集数据，所有 KPI 显示空状态是正常的，这一步只验证"系统能启动、能登录、能渲染"。

---

## §4 接下来想做什么？

| 想做的事 | 去看 |
|---|---|
| 真正部署到生产环境 | [installation-guide.md](./installation-guide.md) |
| 看每个变量是什么 | [../config/environment-variables.md](../config/environment-variables.md) |
| 了解平台能做什么 | [../product/product-overview.md](../product/product-overview.md) |
| 各角色怎么用 | [../product/user-guide.md](../product/user-guide.md) |
| 接现场 PLC / 仪表 | [../product/collector-protocols-user-guide.md](../product/collector-protocols-user-guide.md) |

---

## §5 清理

试完想拆掉：

```bash
docker compose down -v   # 同时删掉数据卷
```

⚠️ `-v` 会清空 Postgres / InfluxDB / 上传文件目录。如果想保留数据下次再起，去掉 `-v`。

---

## §6 没起来？

| 现象 | 原因 | 处置 |
|---|---|---|
| `docker compose ps` 看到 factory-ems 反复重启 | 一般是端口 8888 被占 | 改 `docker-compose.yml` 的 `8888:80` 为别的端口（如 `9000:80`），重起 |
| 浏览器打开后显示 502 | 后端还没起完 / nginx upstream 缓存 | 等 30 秒再刷新；还不行 `docker compose restart nginx` |
| 登录返回 "Unauthorized" | DB 种子未跑完 | `docker compose logs factory-ems \| grep -i flyway` 看迁移有没有成功 |
| 拉代码慢 | 网络问题 | 设代理或换镜像源 |

排查长一些的问题进生产部署文档：[installation-guide.md §10](./installation-guide.md)。

---

**相关文档**

- 生产部署向导：[installation-guide.md](./installation-guide.md)
- 完整安装手册（含可选项）：[installation-manual.md](./installation-manual.md)

**装好之后按顺序上线（"装-通-看-警-钱-报-效"）**

1. 选型：[meter-selection-guide.md](./meter-selection-guide.md)
2. 现场施工：[field-installation-sop.md](./field-installation-sop.md)
3. 看板上线：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
4. 5 分钟演示：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
5. 报警上线：[alarm-commissioning-sop.md](./alarm-commissioning-sop.md)
6. 账单上线：[billing-commissioning-sop.md](./billing-commissioning-sop.md)
7. 月报自动化：[report-automation-sop.md](./report-automation-sop.md)
8. 生产能效：[production-energy-sop.md](./production-energy-sop.md)
