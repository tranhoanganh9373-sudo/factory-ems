# 报表自动化 SOP（月度 PDF 自动出 + 邮件推送）

> **场景**：账期已 LOCKED 后，每月 1 号自动跑上月月报 → 出 PDF → 邮件发给厂长 / 财务 / 各车间主任。
>
> **前置**：
> - `billing-commissioning-sop.md` 已完成，至少有 1 个 LOCKED 账期
> - 一台能 SMTP 发邮件的服务器（或者公司内部 SMTP relay）
> - ADMIN JWT Token

---

## 0. 产品给的 vs 你要自建的（关键诚实告知）

`ems-report` v1.3 提供：

| 能力 | 状态 | 来源 |
|---|---|---|
| 5 张预设报表（日/月/年/班次/月度成本） | ✅ GA | `ReportPresetController.java` |
| 同步 CSV / 异步 token 下载 | ✅ GA | `ReportController.java` |
| 矩阵 Excel / PDF 导出 | ✅ GA | `ReportExportController.java` |
| **报表订阅 / 邮件推送** | ❌ **不在 v1.3** | `report-feature-overview.md §5` 明确写了"规划在 v2.x" |

所以**月度自动邮件**这件事，需要你**自建一层薄薄的调度 + 邮件桥接**：

```
crontab (每月 1 号 06:00) ──┐
                          ▼
            scripts/monthly-report-mail.sh
                          │
                          ├─> POST /api/v1/reports/export (异步)
                          ├─> 轮询 GET /reports/export/{token} 拿 PDF
                          └─> sendmail / msmtp 发到收件人
```

不要等 v2.x —— 这个外挂方案 50 行 bash + cron，**今天就能上线**。

---

## 1. 步骤 ①：先用 curl 跑通预设报表

API 验证（保证产品侧是好的）：

```bash
TOKEN="<jwt>"
BASE="https://ems.example.com"

# 1.1 月报：2026 年 5 月，全厂节点
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/report/preset/monthly?yearMonth=2026-05&orgNodeId=1&energyTypes=ELEC" | jq '.data | length'
# 期望: 31（5 月 31 天每天一行）

# 1.2 月度成本报：5 月，按楼层展开
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/report/preset/cost-monthly?yearMonth=2026-05&orgNodeId=1" | jq
# 期望: 各楼层节点的电费 + 4 段电价占比 + 同环比

# 1.3 日报：5 月 15 日，1F
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/report/preset/daily?date=2026-05-15&orgNodeId=2&energyTypes=ELEC" | jq '.data | length'
# 期望: 96（15 min 粒度，24h × 4）
```

任何一个返回空或报错就先按 §5 故障速查解决，再做下一步。

---

## 2. 步骤 ②：触发异步 PDF 导出

`POST /api/v1/reports/export` 入参（验证自 `ReportExportRequest.java`）：

```bash
# 2.1 提交导出
RESP=$(curl -s -X POST "$BASE/api/v1/reports/export" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "format": "PDF",
    "preset": "COST_MONTHLY",
    "params": {
      "yearMonth": "2026-05",
      "orgNodeId": 1,
      "energyTypes": ["ELEC"]
    }
  }')
TOKEN_FILE=$(echo "$RESP" | jq -r '.data.token')
echo "Got token: $TOKEN_FILE"

# 2.2 轮询状态（status: PENDING → RUNNING → SUCCESS/FAILED）
while true; do
  STATUS=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/v1/reports/export/$TOKEN_FILE" | jq -r '.data.status')
  echo "status=$STATUS"
  [[ "$STATUS" == "SUCCESS" || "$STATUS" == "FAILED" ]] && break
  sleep 5
done

# 2.3 下载 PDF
curl -sS -o /tmp/report-2026-05.pdf -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/report/file/$TOKEN_FILE"
# 注意：file 端点是 /api/v1/report/file/{token}，不是 /reports/export/{token}
# /reports/export/{token} 返回元数据，/report/file/{token} 才是文件流
```

> 异步导出文件落在 `EMS_REPORT_EXPORT_BASE_DIR`（默认 `/data/ems_uploads/exports/`），token **7 天过期**。

---

## 3. 步骤 ③：用 cron + 桥接脚本自动发邮件

把上面的逻辑封装到一个 bash 脚本（已交付：`scripts/monthly-report-mail.sh`），cron 每月 1 号 06:00 跑：

### 3.1 装邮件客户端（任选一个）

```bash
# Debian/Ubuntu — 简单 SMTP relay
sudo apt-get install msmtp msmtp-mta mailutils

# /etc/msmtprc（root 600）
defaults
auth           on
tls            on
tls_starttls   on
logfile        /var/log/msmtp.log

account        report-bot
host           smtp.example.com
port           587
from           report-bot@ems.example.com
user           report-bot@ems.example.com
password       <SMTP_PASSWORD>

account default : report-bot
```

> 公司内部如果有 SMTP relay 不要密码，host + port 即可、`auth off`。

### 3.2 配 cron

```cron
# /etc/crontab — 每月 1 号 06:00 跑上月月报
0 6 1 * * ems  EMS_BASE_URL=https://ems.example.com EMS_TOKEN=<bot-jwt> \
              REPORT_RECIPIENTS="boss@example.com,finance@example.com,ops@example.com" \
              /opt/factory-ems/scripts/monthly-report-mail.sh >> /var/log/ems-report.log 2>&1
```

> bot 的 JWT Token：建一个最小权限账号（READONLY 角色）专门给脚本用，不要复用 ADMIN token。
> 长期有效的 token 走 `POST /api/v1/auth/login` + `expiresIn` 设大一点，或者每次脚本执行前先登录拿 token（更安全）。

### 3.3 验证

```bash
# 手动跑一次（dry-run）
EMS_BASE_URL=https://ems.example.com EMS_TOKEN=<jwt> \
  REPORT_RECIPIENTS="me@example.com" \
  REPORT_DRY_RUN=1 \
  /opt/factory-ems/scripts/monthly-report-mail.sh
```

`REPORT_DRY_RUN=1` 只下 PDF 不发邮件，便于先确认 PDF 内容 OK。

---

## 4. 步骤 ④：验收清单

- [ ] 5 个预设报表 API（daily / monthly / yearly / shift / cost-monthly）curl 都返回非空数据
- [ ] `POST /reports/export` 提交 + 轮询 + 下载 3 步走通，PDF 大小 > 10 KB
- [ ] PDF 打开内容正确：包含楼层划分、电费数字、4 段电价占比、同比环比
- [ ] msmtp / mailx 能发出测试邮件到收件人邮箱
- [ ] cron entry 已加，`crontab -l -u ems` 能看到
- [ ] **`REPORT_DRY_RUN=1` 跑通** 且 PDF 看着对
- [ ] **第一次月度跑** 之后 `/var/log/ems-report.log` 显示 success，所有收件人收到邮件
- [ ] 把 PDF 落盘地址定期清理（cron 同时跑 `find /data/ems_uploads/exports -mtime +30 -delete`）

---

## 5. 故障速查

| 现象 | 排查路径 |
|---|---|
| 预设报表返回 `[]` | ① 时间窗内没数据（看 `/dashboard` 当时有没有数）② orgNodeId 错（去 `/api/v1/org-nodes/tree` 对）③ 用户的节点权限不覆盖该节点 |
| 月度成本报全是 0 | ① 该月没 LOCKED 账单（`/api/v1/bills/periods/2026-05`）② 电价方案没生效（`tariff/resolve` 试）③ 看 `billing-commissioning-sop.md` 第 §6 |
| `POST /reports/export` 返回 token 但永远 PENDING | 服务端线程池占满了。看 `ems-app` 日志找 `ReportExportExecutor`；前面 token 没下载会一直占线程 |
| token SUCCESS 但下载是 0 字节 | `EMS_REPORT_EXPORT_BASE_DIR` 容器内不可写 / 磁盘满；`docker exec ems-app ls -la /data/ems_uploads/exports/` |
| PDF 中文乱码 | OpenPDF 需要嵌入 CJK 字体（思源黑体）；后端启动时报 `Font not found` 即此原因，需把字体文件挂到容器 |
| 邮件发不出去 | `msmtp -d --account=report-bot --from=...` 单独测；多半是 SMTP 密码错 / 端口被防火墙挡 / 收件人黑名单 |
| Cron 跑了但没邮件 | 检查 `/var/log/ems-report.log`；典型 token 过期（>7 天）—— 脚本要从最近一次 LOCKED 账期取 yearMonth |
| 同时多月报合并发一封 | 不支持。脚本一次只发一份；要合并的话改 `monthly-report-mail.sh` 把多份 PDF 用 `pdfunite` 合并再发 |

---

## 6. 与下一阶段的衔接

报表自动化跑通后，路线图剩最后一块：

- **生产能效**（`ems-production`）：录入产量 → 计算"单位产品能耗" → 节能改造前后效果对比。**报表里 `单位产量能耗` 这一列只有产量数据后才有意义**。

至此 EMS 的"装-通-看-警-钱-报" 全闭环。

---

**相关文档**

- 报表产品介绍：[../product/report-feature-overview.md](../product/report-feature-overview.md)
- 选型指南：[meter-selection-guide.md](./meter-selection-guide.md)
- 现场施工 SOP：[field-installation-sop.md](./field-installation-sop.md)
- 看板上线 SOP：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
- 5 分钟演示：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
- 告警上线 SOP：[alarm-commissioning-sop.md](./alarm-commissioning-sop.md)
- 账单上线 SOP：[billing-commissioning-sop.md](./billing-commissioning-sop.md)
- 生产能效 SOP：[production-energy-sop.md](./production-energy-sop.md)
- 桥接脚本：`scripts/monthly-report-mail.sh`
