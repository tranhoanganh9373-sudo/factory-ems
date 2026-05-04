# 平面图（ems-floorplan）· 功能概览

> 适用版本：v1.3.0+ ｜ 最近更新：2026-05-01
> 受众：销售 / 客户 / 运维 / 实施工程师

---

## §1 一句话价值

把工厂的车间平面图（CAD 转 PNG / JPG）上传到系统，再把每块仪表拖到底图上对应位置。这样运维一打开页面就能看到全场仪表的实时状态和读数，红黄绿三色对应在线、即将报警、离线，断线哪个、漏水哪里、能耗高在哪个区域，都不用再翻列表。

---

## §2 解决什么问题

- **看仪表只能看列表**：500 块仪表的状态表，看不出"哪个区域同时变红 = 局部网络问题"或"靠近某台机床的几个表能耗都高 = 这台机床有问题"。
- **新人不熟悉现场**：靠口头讲解和翻施工图，耗时长且记不住，看到报警不知道仪表在哪。
- **客户参观和汇报场景**：领导 / 评审 / 客户参观时，给一张工厂俯视图叠加实时数据是最直观的"科技感展示"。
- **运维巡检低效**：拿着手册一台一台去看，不如开着平面图边走边定位。

---

## §3 核心功能

- **底图上传**：上传车间 / 厂区平面图，支持 PNG / JPG。建议从 CAD 导出后用工具转成栅格图。
- **多张底图**：一个工厂可以有多张图（一楼 / 二楼 / 厂区俯视图 / 配电室特写），每张图独立挂仪表。
- **仪表挂点（points）**：把仪表拖到底图上，记录 (x, y) 坐标。一个仪表可以同时挂在多张底图上（如总表既出现在配电室图也出现在厂区俯视图）。
- **实时态显示**：每个挂点实时显示读数（kW / kvarh / m³ / kg）+ 在线状态色（绿色 = 在线、黄色 = 即将报警、红色 = 离线 / 报警）。
- **悬停详情**：鼠标悬停看仪表名称、最近读数、最近一次成功时间、所属节点。
- **点击下钻**：点挂点跳转到 `/dashboard?meterId=X` 看该表的详细趋势。
- **底图与节点关联**：每张图绑定到某个组织节点（如"主厂区图" → 主厂区节点），权限范围按节点过滤。
- **轻量编辑**：在前端直接拖动仪表挂点位置，保存后立即生效。

---

## §4 适用场景

### 场景 A：客户参观 / 领导汇报

把"厂区俯视图"做主图。讲解时打开 `/floorplan/<id>`，全场仪表实时态一屏呈现。讲到节能改造时，可指出"看，这个区域今年装了变频，绿色读数普遍比邻区低 20%"。

### 场景 B：运维早班巡检

进 `/floorplan` 列表，逐张底图扫一遍。如果某张图突然出现 3 个红点集中在同一区域，初步判断是局部网络故障，先到现场看交换机 / 网线 / PLC，比一台台仪表挨查节省时间。

### 场景 C：新员工入职培训

给新员工分配查看者（VIEWER）账号，让他们花一周熟悉平面图——把每块仪表的位置、含义、所属设备建立物理认知。后续做运维 / 财务工作时定位会快得多。

### 场景 D：故障定位

现场报告"包装车间冷却水压不稳"，运维进对应底图，看冷却水路上几个流量计的实时读数 → 哪个段流量异常 → 现场往哪个段查。

---

## §5 不在范围（首版）

- **不做矢量底图编辑**：上传的是 PNG / JPG 栅格图，不支持在系统里画线 / 改图层 / 缩放无失真。要更新底图时重新上传。
- **不做 3D 视图**：仅 2D 平面，不支持 3D 楼宇模型 / VR 漫游。
- **不做轨迹回放**：不支持"回放过去 1 小时各仪表的状态变化"。这种诉求建议走报表 + 仪表盘组合。
- **不做底图自动定位**：上传底图后仪表挂点位置全靠人工拖拽；不做"图像识别自动找设备位置"。
- **不做控制操作**：平面图只读不控，不支持点击下发开关命令（控制是 SCADA 的职责，参见产品介绍 §5）。
- **不限制底图大小**：但建议 < 5 MB（大于 10 MB 加载慢），分辨率 1920×1080 足够。

---

## §6 与其他模块的关系

```
ems-orgtree              底图绑定到组织节点
       │
       ▼
ems-floorplan
       ├──────< ems-meter        挂点关联到具体仪表
       ├──────< ems-collector    实时数据来源
       ├──────< ems-alarm        挂点变红色受报警状态驱动
       └──────> ems-dashboard    /floorplan/{id}/live 端点供仪表盘嵌入
```

平面图同时承担运维和演示两个用途：运维用来快速定位故障，对外可以做可视化展示。

---

## §7 接口入口

- **前端路径**：
  - `/floorplan` — 底图列表
  - `/floorplan/editor/:id` — 编辑页（拖挂点 + 改属性）
- **API 前缀**：`/api/v1/floorplans`
- **关键端点**：
  - `GET /api/v1/floorplans` — 列表
  - `GET /api/v1/floorplans/{id}` — 单图详情含挂点
  - `POST /api/v1/floorplans/upload` — 上传底图（multipart）
  - `PUT /api/v1/floorplans/{id}` — 改名 / 改节点关联
  - `DELETE /api/v1/floorplans/{id}` — 删除
  - `PUT /api/v1/floorplans/{id}/points` — 一次性更新挂点（含位置和绑定的仪表）
  - `GET /api/v1/floorplans/{id}/image` — 获取底图原图（前端 `<img src>`）
  - `GET /api/v1/dashboard/floorplan/{id}/live` — 实时态查询（仪表盘嵌入用）

---

## §8 关键字段

### `floorplans` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `name` | varchar | 底图名（"主厂区俯视图"等）|
| `org_node_id` | bigint | 关联节点 |
| `image_path` | varchar | 文件相对路径（默认 `data/ems_uploads/floorplans/{id}.png`）|
| `width` / `height` | int | 图片像素宽高 |
| `points` | jsonb | 挂点数组：`[{meter_id, x, y, label}, ...]` |
| `enabled` | bool | 启用开关 |
| `created_at` / `updated_at` | timestamp | |

> `points` 字段以 JSON 数组形式存储，每个元素包含 `meter_id`、相对底图坐标 `(x, y)`、可选 `label`。前端按底图原始尺寸渲染，浏览器缩放时按比例换算。

---

## §9 文件存储

- 默认落到容器内 `/data/ems_uploads/floorplans/`
- 主机侧挂载点：`./data/ems_uploads/floorplans/`
- nginx 反向代理给前端只读访问：`/uploads/floorplans/<file>`
- 大文件建议进对象存储（S3 / OSS），通过 `EMS_FLOORPLAN_BASE_DIR` 改路径

---

**相关文档**

- 仪表与计量：[meter-feature-overview.md](./meter-feature-overview.md)
- 仪表盘：[dashboard-feature-overview.md](./dashboard-feature-overview.md)
- 组织树：[orgtree-feature-overview.md](./orgtree-feature-overview.md)
- 报警：[alarm-feature-overview.md](./alarm-feature-overview.md)
- 平台总览：[product-overview.md](./product-overview.md)
