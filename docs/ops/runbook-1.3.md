# Runbook — Plan 1.3 运维手册

> 适用范围：factory-ems Plan 1.3（电价、产量、平面图、看板⑥⑦⑧⑨、Excel/PDF 报表、异步导出）。
> 与 [`runbook.md`](./runbook.md) 互补；本文聚焦 1.3 新增能力的日常运维。

---

## 1. 平面图（Floorplan）

### 1.1 存储路径布局

```
${EMS_UPLOAD_ROOT}/                        # docker-compose 内为 ./data/ems_uploads
├── floorplans/
│   ├── 2026-04/                           # 上传月份分桶
│   │   ├── 6b1f8a3e-9d12-4c2a-b1e7-0f9c3d4a5e7b.png
│   │   ├── 9c2e1d4f-...........webp
│   │   └── ...
│   ├── 2026-05/
│   │   └── ...
└── exports/                               # 报表异步导出落盘（fileToken）
    ├── ad-hoc-2026-04-25T120030Z.csv
    └── monthly-2026-04-LINE-A-2026-04-25T130000Z.xlsx
```

约定：

- **目录分桶**：`yyyy-MM`，按上传月份切分，避免单目录 inode 爆炸。
- **文件名**：UUIDv4 + 扩展名。Spring 在 `FloorplanService.upload()` 生成；
  原始文件名仅在 DB `floorplans.original_name` 留档，磁盘上不再使用。
- **支持扩展**：`png` / `jpg` / `jpeg` / `webp` / `svg`。MIME 校验在
  `ems-floorplan` 的 `FileTypeValidator` 完成；上限 10 MiB。
- **DB 字段** `floorplans.image_path`：相对路径，例如
  `2026-04/6b1f8a3e-9d12-4c2a-b1e7-0f9c3d4a5e7b.png`。

### 1.2 图片 URL 路由（Nginx 直出）

`GET /api/v1/floorplans/{id}/image?path=...` 由 Nginx 直接 `alias` 到磁盘文件，
**不经过 Spring**。详见 [`nginx-setup.md`](./nginx-setup.md)。

校验链：

1. Spring `GET /api/v1/floorplans/{id}` 鉴权后返回 metadata，包含完整 image_url。
2. 浏览器加载 image_url → 命中 `nginx/conf.d/factory-ems.conf` 的
   `location ~ ^/api/v1/floorplans/(\d+)/image$`。
3. Nginx 用 `alias /var/www/uploads/floorplans/$arg_path` 直出文件流。
4. 应答 `Cache-Control: public, max-age=86400, immutable`，浏览器缓存 1 天。

**故障排查：**

| 症状 | 排查 |
|---|---|
| 浏览器看到 404 | `docker compose exec nginx ls /var/www/uploads/floorplans/` 验证文件存在 |
| 403 | 容器内文件权限：`docker compose exec nginx stat /var/www/uploads/floorplans/...` |
| 编辑器拖拽点位保存后刷新丢失 | 看 `floorplan_points` 表是否落库；POST `/floorplan/{id}/points` 应 200 |
| 图片很大加载慢 | 确认 Nginx 应答含 `Accept-Ranges: bytes` 且 `sendfile on` 生效 |

### 1.3 上传 / 删除

- **上传** `POST /api/v1/floorplans` (multipart) → Service 写盘 + DB。
- **删除** `DELETE /api/v1/floorplans/{id}` → DB 级联删 `floorplan_points`，
  并标记 `image_path` 待清理。**磁盘文件不立即删**（避免误删，等周期 GC）。
- **GC 任务**：`scripts/floorplan-gc.sh` 扫 `data/ems_uploads/floorplans/`，
  跨表 join `floorplans.image_path`，孤儿文件移到 `data/ems_uploads/.trash/`。
  建议每月 1 号 cron 跑一次。

---

## 2. PDF 模板编辑

Plan 1.3 用 **OpenHTMLToPDF**（不是 JasperReports）渲染 PDF。模板就是普通的
HTML + 内联 CSS，由 `PdfExporter.renderHtml()` 生成。

### 2.1 模板位置

- 主类：`ems-report/src/main/java/.../export/PdfExporter.java`
- 辅助：`ems-report/src/main/resources/reports/`（CSS / 字体 / 公司 LOGO）
- 字体：中文 fallback 用思源黑体，注册见
  `PdfExporter.buildRenderer()` 中的 `useFont(...)` 调用。

### 2.2 修改步骤

1. 编辑 `PdfExporter.renderHtml(ReportMatrix matrix)` 中拼接的 HTML 字符串。
   - 表头 / 表尾 / 公司 LOGO / 水印都在这里。
   - 样式必须 **inline 或 `<style>` 块**；OpenHTMLToPDF 不支持外链 CSS 跨域。
2. 改完跑 `./mvnw -pl ems-report test -Dtest=PdfExporterTest` 验证渲染。
3. 视觉验证：测试输出 `target/test-pdf/*.pdf`，肉眼检查中文 / 表格 / 分页。
4. 单元测试覆盖了基础合法性（PDF 以 `%PDF-` 开头），不覆盖样式回归。

### 2.3 典型修改

| 需求 | 改动点 |
|---|---|
| 替换公司 LOGO | `src/main/resources/reports/logo.png`（base64 嵌入 HTML） |
| 增加水印 | `renderHtml()` 中加 `<div class="watermark">...</div>` + CSS `position:fixed` |
| 调整分页 | CSS `page-break-before: always;` 或 `page-break-inside: avoid;` |
| 改字体 | `buildRenderer()` 增加 `useFont(...)`，HTML 用 `font-family: 'XXX'` |

### 2.4 已知限制

- OpenHTMLToPDF 不支持 JS、CSS3 grid / flexbox 高级特性。
- 表格分页需要 `<thead>` 重复表头 → CSS `display: table-header-group;`。
- 中文换行需 `word-break: break-all;`，否则长字符串溢出。

---

## 3. CSV 批量导入产量数据

`ems-production` 的 CSV 导入端点：`POST /api/v1/production/entries/import`
（multipart, `file` 字段）。

### 3.1 文件格式

- **编码**：UTF-8（带或不带 BOM 都可）。
- **分隔符**：英文逗号。
- **首行**：表头（必须存在）。
- **必需列**：`org_node_code, shift_code, entry_date, product_code, quantity, unit`。
- **行分隔**：`\n` 或 `\r\n` 均可。

### 3.2 列定义

| 列 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `org_node_code` | 字符串 | 是 | 组织节点代码，例如 `LINE-A`、`WORKSHOP-1`。Service 用 `org_nodes.code` 查 ID。 |
| `shift_code` | 字符串 | 是 | 班次代码，例如 `MORNING`、`AFTERNOON`、`NIGHT`。Service 用 `shifts.code` 查 ID。 |
| `entry_date` | `yyyy-MM-dd` | 是 | 业务日期。 |
| `product_code` | 字符串 | 是 | 产品代码（自由字符串，不强制外键）。 |
| `quantity` | 数字 | 是 | 数量；保留 3 位小数。 |
| `unit` | 字符串 | 是 | 单位代码：`PIECE` / `KG` / `M` / `M2` / `M3`。 |

### 3.3 示例

```csv
org_node_code,shift_code,entry_date,product_code,quantity,unit
LINE-A,MORNING,2026-04-25,SKU-A001,512.000,PIECE
LINE-A,AFTERNOON,2026-04-25,SKU-A001,488.000,PIECE
LINE-A,NIGHT,2026-04-25,SKU-A001,503.000,PIECE
LINE-B,MORNING,2026-04-25,SKU-B007,12.500,KG
```

### 3.4 错误处理

- 任意一行解析失败 → 整个导入回滚（事务），返回 4xx + JSON 错误清单。
- 错误清单示例：
  ```json
  {
    "error": "VALIDATION_FAILED",
    "rows": [
      { "line": 4, "field": "shift_code", "value": "EVENING", "message": "未知班次代码" },
      { "line": 7, "field": "quantity", "value": "abc", "message": "数字解析失败" }
    ]
  }
  ```
- 重复主键 `(org_node_id, shift_id, entry_date, product_code)` → 默认拒绝；
  携带查询参数 `?upsert=true` 改为更新已有行。

---

## 4. 备份与清理

### 4.1 报表导出落盘清理

- 异步报表导出（Phase J/K/M）成功后落盘到 `data/ems_uploads/exports/`。
- 文件名带 ISO-8601 时间戳，可由 `report_export_tasks` 表关联。
- **保留策略**：默认 7 天，由后台 `ExportFileSweeper` 扫 `created_at < now - 7d`
  且关联 `report_export_tasks.status = COMPLETED` 的记录，删盘 + 删行。
- 手动清理（演练 / 紧急释放磁盘）：
  ```bash
  find data/ems_uploads/exports -type f -mtime +7 -delete
  psql -U ems factory_ems -c \
    "DELETE FROM report_export_tasks WHERE created_at < NOW() - INTERVAL '7 days';"
  ```

### 4.2 fileToken 过期

- Phase M：`/reports/export/{token}` 返回的下载 token **24 小时后失效**。
- 过期后客户端再次请求返回 `410 GONE`；任务记录保留 7 天供审计，磁盘文件
  按上一节策略清理。
- 撤销正在进行的导出：`UPDATE report_export_tasks SET status='CANCELLED'
  WHERE token='...';` 后台任务在下一个心跳点检查并退出。

### 4.3 平面图 GC

见 §1.3。建议 cron：
```
0 3 1 * * /opt/factory-ems/scripts/floorplan-gc.sh >> /var/log/factory-ems/gc.log 2>&1
```

### 4.4 PostgreSQL / InfluxDB 备份

继承自 1.0 / 1.1 的备份脚本，无变化。复习路径：

- Postgres：`pg_dump -U ems factory_ems | gzip > backup-$(date +%F).sql.gz`
- Influx：`influx backup --bucket factory_ems /backup/$(date +%F)`

### 4.5 备份完整性自检

每次备份完成后跑：

```bash
gunzip -t backup-*.sql.gz                              # gzip 校验
psql -U ems backup_test -f <(gunzip -c backup-*.sql.gz)  # 临时库恢复测试
```

---

## 5. 性能基线（参考）

经 Phase U 的 k6 压测（详见 [`../../perf/README.md`](../../perf/README.md)）：

| 场景 | 阈值 | 实测（基线） |
|---|---|---|
| Dashboard 50 VU 30s ramp + 60s hold | p95 < 1000ms | 看 `perf/results/` |
| Monthly Excel 5 VU sequential | end-to-end < 5000ms | 看 `perf/results/` |
| Login 1 VU baseline | p95 < 200ms | 看 `perf/results/` |

低于阈值时先看 `actuator/metrics/http.server.requests` 与 `pg_stat_activity`。
