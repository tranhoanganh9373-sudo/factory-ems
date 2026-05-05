import type { ReactNode } from 'react';

/**
 * 页面级帮助文本。挂在 PageHeader 的 ⓘ 上，hover 显示。
 * 集中放置便于产品/运营同步更新；JSX 写法是为了支持加粗、列表、警告色。
 */

const baseStyle = { fontSize: 12, lineHeight: 1.7 } as const;

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <>
      <div style={{ fontWeight: 600, marginTop: 8, marginBottom: 2, fontSize: 13 }}>{title}</div>
      <div>{children}</div>
    </>
  );
}

export const HELP_AD_HOC_QUERY: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      自由维度的时序数据导出。选时间范围 + 聚合粒度 + 组织/能源/测点过滤 → 一份 CSV。
    </Section>
    <Section title="与「标准报表」的区别">
      日报/月报/年报/班次报表是<b>固定模板</b>，列固定、口径固定；即席查询是<b>临时口径</b>，
      用于排查、自定义对比、给客户出特殊汇总。
    </Section>
    <Section title="聚合粒度怎么选">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>HOUR：1 个月内、需要看小时分布</li>
        <li>DAY：1 季度内、按日对比</li>
        <li>MONTH：跨年、按月对比</li>
      </ul>
      粒度越细行数越多，超大区间请用 DAY/MONTH 或切换异步导出。
    </Section>
    <Section title="同步 vs 异步">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>同步：&lt; 5 万行直接下载 CSV，等 1-3 秒</li>
        <li>
          异步：&gt; 5 万行（如 1 年×全部测点×小时粒度），后台跑，下方任务列表轮询，
          <span style={{ color: '#faad14' }}>token 30 分钟过期、下载一次后失效</span>
        </li>
      </ul>
    </Section>
    <Section title="过滤项留空 = 全部">
      组织节点、能源类型、测点都可不选；不选 = 不限制该维度。
    </Section>
    <Section title="量值类型如何影响结果">
      表计的<b>量值类型</b>（在表计编辑页配置）决定区间合计算法： 周期增量 = 直接相加；累积电量 =
      末点-初点；瞬时功率 = 积分。本页不需关心，引擎自动分派。
    </Section>
  </div>
);

export const HELP_ASYNC_EXPORT: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      即席查询/标准报表选择「异步任务」后产出的后台导出列表。每行 = 一份正在/已经生成的 CSV。
    </Section>
    <Section title="任务状态">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>等待中</b>：刚提交，还在排队
        </li>
        <li>
          <b>生成中</b>：worker 在跑 SQL/InfluxDB
        </li>
        <li>
          <b>就绪</b>：可点「下载」按钮取文件
        </li>
        <li>
          <b>失败</b>：错误原因看 hover；常见为权限、超时、参数错
        </li>
      </ul>
    </Section>
    <Section title="重要约束">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          token 自创建起 <b>30 分钟过期</b>；过期会自动从列表移除
        </li>
        <li>
          下载是<b>一次性</b>的：点完「下载」后该 token 立刻 evict，刷新会消失
        </li>
        <li>
          列表只在<b>当前会话</b>内可见（sessionStorage），换浏览器/重启浏览器会丢
        </li>
        <li>每用户最多保留最近 50 条任务</li>
      </ul>
    </Section>
    <Section title="为什么没有「服务端任务列表」">
      产品决策：导出文件只暂存内存 30
      分钟，不入库；服务端不维护用户的历史任务表，避免存储与隐私问题。如需归档请下载后自己存。
    </Section>
  </div>
);

export const HELP_PRODUCTION_ENTRY: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      录入每个组织节点 × 班次 × 产品 × 日期的产量数据。是「单位产值能耗」KPI 和分摊算法
      PROPORTIONAL.basis=PRODUCTION 的数据来源。
    </Section>
    <Section title="字段说明">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>日期</b>：产出归属日期，按本地时区
        </li>
        <li>
          <b>组织节点</b>：产线/车间，对应 orgtree 中的节点
        </li>
        <li>
          <b>班次</b>：来自班次配置（管理 → 班次管理）
        </li>
        <li>
          <b>产品</b>：自由文本 code，如 「P-001」、「精钢-X12」
        </li>
        <li>
          <b>数量 + 单位</b>：unit 一般 pcs / kg / m³，可自定义
        </li>
      </ul>
    </Section>
    <Section title="主键唯一性">
      (日期, 组织节点, 班次, 产品)
      是逻辑唯一键。重复录入同一组合会被服务端拒绝；要修改请先删除旧记录。
    </Section>
    <Section title="CSV 批量导入">
      列：<code>entryDate,orgNodeId,shiftId,productCode,quantity,unit,remark</code>。 entryDate 用{' '}
      <code>YYYY-MM-DD</code>。返回结果会列出失败行号。
    </Section>
    <Section title="删除影响">
      <span style={{ color: '#faad14' }}>
        该日期所在账期已 CLOSED 的话，对应分摊批次需要重跑才会反映新数据
      </span>
      。 LOCKED 账期下的产量数据不会影响已封存的账单。
    </Section>
  </div>
);

export const HELP_COST_RULES: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      把一张「主表」的能耗按规则<b>分摊</b>给一个或多个组织节点。账单由分摊结果 × 电价 = 金额。
    </Section>
    <Section title="4 种算法">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>DIRECT 直接归集</b>：整张表归 targetOrgIds[0]，无拆分。例：1 车间 1 块独占电表。
        </li>
        <li>
          <b>PROPORTIONAL 比例分摊</b>：按 basis 拆给多个 org。 basis 可选 <code>FIXED</code>
          （手填权重）/ <code>AREA</code>（按面积）/ <code>HEADCOUNT</code>（按人数）/{' '}
          <code>PRODUCTION</code>（按产量）。
        </li>
        <li>
          <b>RESIDUAL 差值分摊</b>：(总表 - 子表们) = 公共/损耗部分，再 PROPORTIONAL
          拆。例：园区总表减去各车间表 = 公共照明。
        </li>
        <li>
          <b>COMPOSITE 复合</b>：把多个子规则链式串起来，用于跨层级先扣后分。
        </li>
      </ul>
    </Section>
    <Section title="主表 vs 目标 org">
      <b>主表</b>是数据源（哪块电表的读数）；<b>目标 org</b>是被记账方（kWh × tariff
      算到谁头上）。 RESIDUAL 时 deductMeterIds 是要扣的子表（通常 = 各车间分表）。
    </Section>
    <Section title="weights JSON 模板">
      PROPORTIONAL：
      <code>{`{ "basis": "FIXED", "values": { "10": 0.6, "11": 0.4 } }`}</code>
      <br />
      RESIDUAL：
      <code>{`{ "deductMeterIds": [3,4], "values": { "10": 1.0 } }`}</code>
      <br />
      COMPOSITE：子规则 id 数组（按优先级顺序执行）
    </Section>
    <Section title="优先级">
      数字越小越先执行。COMPOSITE 内部按 priority 排；同一规则集内 RESIDUAL 必须在它依赖的
      PROPORTIONAL 之后。
    </Section>
    <Section title="生效区间">
      规则只对 <code>effectiveStart ≤ period &lt; effectiveEnd</code> 的批次生效。
      改电费/搬车间时新建一条带新区间的规则，老规则保留以便重跑历史账期。
    </Section>
    <Section title="Dry-run">不写库，预演当前规则在某 period 上的分摊结果。改 weights 前必跑。</Section>
  </div>
);

export const HELP_COST_RUNS: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      把分摊规则应用到一段时间，产出每个 org × 能源的 kWh / 金额结果。是<b>账单生成的前置依赖</b>。
    </Section>
    <Section title="状态机">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>PENDING</b> 待运行：刚提交，等 worker 调度
        </li>
        <li>
          <b>RUNNING</b> 运行中：在跑 SQL + 写入结果
        </li>
        <li>
          <b>SUCCESS</b> 成功：可被账期关账时引用
        </li>
        <li>
          <b>FAILED</b> 失败：错误信息看 errorMessage 列；常见为规则 weights JSON 格式错、period
          内无数据
        </li>
        <li>
          <b>SUPERSEDED</b> 已覆盖：同 period 又跑了一次新 run，旧 run 自动失效，账期关账只用最新
          SUCCESS
        </li>
      </ul>
    </Section>
    <Section title="提交参数">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>账期 period</b>：<code>[periodStart, periodEnd)</code> 半开区间，建议对齐到自然月末
          23:59:59
        </li>
        <li>
          <b>规则</b>：留空 = 自动选所有 enabled 且生效的规则；指定 = 仅跑该子集（用于重跑某条规则）
        </li>
      </ul>
    </Section>
    <Section title="为什么列表只看到本会话提交的">
      服务端没有 "按 period 列 run" 的端点；前端用 sessionStorage 持有最近 50 个 runId 单条轮询。
      跨浏览器/换设备看不到他人提交的批次；如要全局视图请走管理 → 审计日志。
    </Section>
    <Section title="常见操作流程">
      <ol style={{ paddingLeft: 18, margin: 0 }}>
        <li>月初校对完产量录入 + 电价后，新建批次跑当月</li>
        <li>SUCCESS 后点 #ID 进详情查每行分摊明细</li>
        <li>账期管理页对该 period 点「关账期 + 生成账单」</li>
      </ol>
    </Section>
  </div>
);

export const HELP_BILL_PERIODS: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      月度账期（period）的生命周期管理。一个账期 = 一个 <code>YYYY-MM</code>
      ，决定该月账单何时生成、何时封存。
    </Section>
    <Section title="状态机">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>OPEN 开放</b>：新建后默认状态。可重复跑分摊批次，账单尚未生成。
        </li>
        <li>
          <b>CLOSED 已关闭</b>
          ：已点「关账期 + 生成账单」，账单已落库；仍可重新生成（覆盖原账单）或锁定。
        </li>
        <li>
          <b>LOCKED 已锁定</b>：账单封存。任何写操作（包括重跑分摊、重生成账单）都被拒绝。
          <span style={{ color: '#ff4d4f' }}>仅 ADMIN 可解锁</span>。
        </li>
      </ul>
    </Section>
    <Section title="关账期做了什么">
      <ol style={{ paddingLeft: 18, margin: 0 }}>
        <li>取该账期对应的最新 SUCCESS 分摊批次</li>
        <li>叠加电价生成每个 org 的账单行</li>
        <li>写入 bills 表，状态变 CLOSED</li>
      </ol>
      <span style={{ color: '#faad14' }}>关账前请确认产量数据已录全、分摊批次已 SUCCESS</span>。
    </Section>
    <Section title="重新生成">
      CLOSED 状态下可再点一次。会用最新分摊结果覆盖账单。LOCKED 后此按钮不可用。
    </Section>
    <Section title="锁定 / 解锁的双重确认">
      为防止误操作，必须输入完整的 <code>我确认锁定 YYYY-MM</code> /{' '}
      <code>我确认解锁 YYYY-MM</code> 才生效。两次操作都会写审计日志。
    </Section>
    <Section title="什么时候该锁">
      账单已交付/已对账/已结算后立刻锁。锁定后即使 ADMIN 解锁也会留痕，符合 SOX 等审计要求。
    </Section>
  </div>
);

export const HELP_BILL_LIST: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      按账期 + 组织 + 能源过滤查看月度账单明细。一行 = 一个 (账期, 组织, 能源) 的合计账单。
    </Section>
    <Section title="数据何时出现">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          账期状态为 <b>OPEN 开放</b>：列表为空。需要先在「账期管理」点「关账期 + 生成账单」。
        </li>
        <li>
          状态为 <b>CLOSED 已关闭</b> 或 <b>LOCKED 已锁定</b>：本页才有数据。
        </li>
        <li>
          重新生成账单（CLOSED 状态下再次关账）会<b>覆盖</b>本页所列内容；LOCKED 后不可再写。
        </li>
      </ul>
    </Section>
    <Section title="尖 / 峰 / 平 / 谷">
      四列分时电费。账单生成时按当月有效电价方案的时段切分计算， 总<b>金额 = 尖 + 峰 + 平 + 谷</b>
      （水/气等非分时能源时，全部进「平」一栏）。
    </Section>
    <Section title="用量 vs 金额 vs 单位成本">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>用量</b>：分摊批次给该 org 的 kWh / m³ 等
        </li>
        <li>
          <b>金额</b>：用量 × 电价（按时段加权）
        </li>
        <li>
          <b>产量</b>：来自「日产量录入」该 org 当月汇总；多产品按 unit 自动归并
        </li>
        <li>
          <b>单位成本</b> = 金额 ÷ 产量。产量为空则显示 —。账期内换产线/产品口径会失真。
        </li>
      </ul>
    </Section>
    <Section title="过滤项">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>账期：必选；URL 参数 ?periodId 可分享</li>
        <li>组织过滤：选父节点不会自动展开子孙；要看下级请逐个选</li>
        <li>能源过滤：按 ELEC / WATER / GAS / STEAM / OIL 过滤</li>
      </ul>
    </Section>
    <Section title="导出 Excel">
      右上「导出 Excel (COST_MONTHLY)」走<b>异步</b>预设报表通道，含尖峰平谷与产量列；
      最长等 2 分钟，下载链接一次性。如要别的口径请用「即席查询」。
    </Section>
    <Section title="为什么我看到 0 行">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>账期还是 OPEN —— 去关账期</li>
        <li>分摊规则没覆盖到该 org —— 去「分摊规则」校对 targetOrgIds</li>
        <li>该 org 当月没数据 —— 检查表计/采集器是否在线</li>
      </ul>
    </Section>
  </div>
);

export const HELP_TARIFF: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      电价/水价/气价时段方案。一个方案 = 能源类型 + 生效日期 + 一天 24h 的分时价格（尖/峰/平/谷）。
      账单生成时把分摊出的 kWh 按方案切分时段计费。
    </Section>
    <Section title="时段约束">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          所有时段拼起来必须<b>覆盖整 24h、不重叠、不留缝</b>，否则保存会报错。
        </li>
        <li>
          跨零点时段写法：把 22:00–06:00 拆成 22:00–24:00 与 00:00–06:00 两段，<b>periodType 相同</b>
          。
        </li>
        <li>
          单位为「元/kWh」（电）、「元/m³」（水气）等；UI 不强制单位，按对应能源 unit 填。
        </li>
      </ul>
    </Section>
    <Section title="生效区间">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>effectiveFrom</b>：起始日期，按本地时区 00:00 起算
        </li>
        <li>
          <b>effectiveTo</b>：终止日期；留空 = ∞（永久生效，直到下一个方案接力）
        </li>
        <li>
          同一能源类型同一天<b>只能有一个方案启用</b>。新方案覆盖旧方案前请把旧方案 effectiveTo
          填上。
        </li>
      </ul>
    </Section>
    <Section title="尖 / 峰 / 平 / 谷">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>SHARP 尖</b>：电网最贵时段（夏季 19-21 点等）
        </li>
        <li>
          <b>PEAK 峰</b>：常规高峰
        </li>
        <li>
          <b>FLAT 平</b>：平段；非分时能源（水/气）建议<b>整天一段都写 FLAT</b>
        </li>
        <li>
          <b>VALLEY 谷</b>：低谷（夜间 23-7 点等）
        </li>
      </ul>
    </Section>
    <Section title="启用开关">
      关掉的方案<b>不会被账单引擎选中</b>，但已 CLOSED/LOCKED 的历史账单不变（账单写库时电价已固化）。
    </Section>
    <Section title="改电价后什么时候生效">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>下一次「关账期 + 生成账单」开始按新电价计算</li>
        <li>
          已 CLOSED 的账期：<b>不会自动回溯</b>。如需追溯，先解锁/重生成账单（LOCKED 需 ADMIN）。
        </li>
        <li>
          <span style={{ color: '#faad14' }}>
            涨价/降价请提前 1-2 天录入，避免月底跨日窗口不一致
          </span>
          。
        </li>
      </ul>
    </Section>
    <Section title="新建建议">
      先复制相近的方案做参考；24h 时段图（列表「24h 时段覆盖」列）可视化校验是否填全。
    </Section>
  </div>
);

export const HELP_ALARM_HEALTH: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      报警子系统的<b>实时健康面板</b>。30 秒自动刷新一次，是巡检/早会的快速概览。
    </Section>
    <Section title="指标含义">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>测点总数</b>：系统当前注册的全部 meter 数量
        </li>
        <li>
          <b>在线测点</b>：collector 最近一次轮询有读数的 meter
        </li>
        <li>
          <b>离线测点</b>：超过「静默超时」未上报的 meter（含维护中）
        </li>
        <li>
          <b>报警中</b>：当前 status=ACTIVE 的 alarm 数（去重后；同一设备同一类型只算 1 条）
        </li>
      </ul>
    </Section>
    <Section title="不一致排查">
      在线 + 离线 ≠ 总数 时，差额 = 「未启用」或「无 channel」的 meter。 去表计列表按 enabled=false /
      channelId is null 过滤可定位。
    </Section>
    <Section title="去哪里处理报警">
      点指标无法联动。请到「报警 → 历史」按设备/类型筛选并 ACK / RESOLVE。
    </Section>
  </div>
);

export const HELP_ALARM_HISTORY: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      历史报警事件流水，每行 = 一次触发。状态机：<b>ACTIVE → ACKNOWLEDGED → RESOLVED</b>。
    </Section>
    <Section title="状态机">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>ACTIVE 触发中</b>：尚未确认；后台条件复发会刷新 lastTriggeredAt 但不新建一条
        </li>
        <li>
          <b>ACKNOWLEDGED 已确认</b>：值班人员确认已知晓，问题尚未解决；webhook 不再重复推
        </li>
        <li>
          <b>RESOLVED 已解决</b>：触发条件消失或人工标记结束
        </li>
      </ul>
    </Section>
    <Section title="报警类型">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>SILENT_DEVICE</b>：长时间无读数（阈值在「报警规则」配置）
        </li>
        <li>
          <b>COMMUNICATION_FAULT</b>：连续 N 次采集失败
        </li>
        <li>
          <b>THRESHOLD_*</b>：用量/功率越限（如配置）
        </li>
      </ul>
    </Section>
    <Section title="批量操作">
      多选后可批量 ACK / RESOLVE。删除是软删除，仅管理员可见审计回溯。
    </Section>
    <Section title="过滤建议">
      时间范围必填；高峰期建议先按 type + status=ACTIVE 缩小范围再翻页， 避免一次性拉 5000+ 条卡顿。
    </Section>
  </div>
);

export const HELP_ALARM_WEBHOOK: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      报警事件的<b>外推通道配置</b>。开启后，系统在 ACTIVE 触发时按 adapterType 投递到外部 IM/告警系统。
    </Section>
    <Section title="adapterType（适配器）">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>GENERIC</b>：通用 JSON POST，body 为完整 AlarmDTO
        </li>
        <li>
          <b>DINGTALK</b>：钉钉机器人 markdown 格式，url 直接填机器人 webhook 地址
        </li>
        <li>
          <b>WECHAT_WORK</b>：企业微信机器人
        </li>
        <li>
          <b>FEISHU</b>：飞书机器人
        </li>
      </ul>
    </Section>
    <Section title="字段">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>url</b>：HTTPS 推荐；HTTP 仅在内网测试用
        </li>
        <li>
          <b>secret</b>：HMAC 签名密钥（DingTalk/Feishu 等需校验）；GENERIC 留空
        </li>
        <li>
          <b>timeoutMs</b>：单次投递超时；建议 3000-5000ms
        </li>
      </ul>
    </Section>
    <Section title="投递日志">
      下方表格按时间倒序展示最近投递结果。<b>HTTP 状态 + 响应体前 200 字节</b> 一并落库。
      失败会按指数退避重试 3 次。
    </Section>
    <Section title="抑制窗口">
      同一报警在「报警规则」配置的全局抑制窗口内只推一次。改 webhook 不会重发历史。
    </Section>
  </div>
);

export const HELP_ALARM_RULES: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      报警阈值规则。<b>全局默认</b>来自后端 application.yml，<b>设备级覆盖</b>
      用于个别表/产线偏离默认值。
    </Section>
    <Section title="字段含义">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>静默超时（秒）</b>：collector 多久没收到该设备的读数就触发 SILENT_DEVICE 报警。 默认值适合
          5s 轮询；如果某表设置成 1 分钟轮询，需要提高到 ≥ 180s 否则会误报。
        </li>
        <li>
          <b>连续失败次数</b>：连续 N 次采集失败才触发 COMMUNICATION_FAULT。提高 N 可减少抖动误报，
          降低 N 可更早发现问题。默认 5 次（约 25s 检测时间）。
        </li>
        <li>
          <b>抑制窗口（秒）</b>：同一报警在该窗口内不重复推送，仅全局可调。
        </li>
      </ul>
    </Section>
    <Section title="维护模式">
      启用后该设备在维护期间触发的报警全部抑制；维护备注（建议格式：原因 + 计划恢复时间 +
      联系人）会写入审计日志。 维护结束后记得关闭，否则报警一直被吃掉。
    </Section>
    <Section title="留空 = 沿用全局默认">
      字段填了数字 = 仅对该设备覆盖；留空 = 跟随全局默认；删除整条覆盖记录则该设备完全沿用全局。
    </Section>
    <Section title="生效时延">
      改配置后<b>最长 60 秒生效</b>（后端有 in-memory cache 1 分钟刷新）；不要保存后立刻测，等 1
      分钟。
    </Section>
    <Section title="设备 ID 怎么查">
      <code>设备 ID = meters.id</code>（不是 meter.code）。表计列表页可见 id 列；或编辑表计 modal
      标题旁。
    </Section>
  </div>
);

export const HELP_ORGTREE: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      组织树（厂 / 车间 / 产线 / 设备）。是<b>所有功能的过滤维度根</b>：表计、报表、账单、权限都靠它。
    </Section>
    <Section title="节点类型">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>PLANT 厂</b>：顶层，一个企业通常 1-3 个
        </li>
        <li>
          <b>WORKSHOP 车间</b>：成本核算粒度
        </li>
        <li>
          <b>LINE 产线</b>：产量录入、能耗对比粒度
        </li>
        <li>
          <b>EQUIPMENT 设备</b>：单台设备/工位
        </li>
      </ul>
    </Section>
    <Section title="操作">
      新建（同级 / 子节点）、编辑、删除（必须先删空）、移动（拖拽到新父）。 删除会级联校验：
      节点上挂的表计/产量/分摊规则未清理时禁止删除。
    </Section>
    <Section title="编码 code 的作用">
      唯一标识，<b>不可重复</b>，建议用业务编号（如 W01、L0103）。CSV
      导入、API、外系统对接都用 code，名称仅作展示。
    </Section>
    <Section title="权限">
      用户是否能看/改某节点由「用户权限」页授权决定； SUBTREE 范围 = 子树全部，NODE_ONLY = 只该节点。
    </Section>
  </div>
);

export const HELP_METERS: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      表计登记表。每行 = 一只物理表（电表/水表/气表/...）+ 它在 InfluxDB 的 tag 标识。
    </Section>
    <Section title="关键字段">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>code</b>：唯一编码，与采集器写 InfluxDB 时的 meter_code 一致；改名会断历史曲线
        </li>
        <li>
          <b>能源类型</b>：ELEC / WATER / GAS / ...，决定单位与电价方案
        </li>
        <li>
          <b>orgNodeId</b>：表计归属组织节点；权限和报表按此过滤
        </li>
        <li>
          <b>channelId</b>：所属采集通道；空 = 未接采集
        </li>
        <li>
          <b>valueKind</b>（量值类型）：决定区间合计算法
        </li>
        <li>
          <b>enabled</b>：关掉后采集器跳过、报警不触发，但历史数据保留
        </li>
      </ul>
    </Section>
    <Section title="量值类型 valueKind">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>INTERVAL_DELTA 周期增量</b>：每条样本 = 1 个轮询周期内的能耗（kWh）。区间合计 = sum
        </li>
        <li>
          <b>CUMULATIVE_ENERGY 累积电量</b>：表底数（单调递增 kWh）。区间合计 = last - first
        </li>
        <li>
          <b>INSTANT_POWER 瞬时功率</b>：每样本 = 当时功率（kW）。区间合计 = ∫P dt（积分）
          <br />
          <span style={{ color: '#faad14' }}>必须把 collector 的 scale 调到 kW，否则差 1000 倍</span>
        </li>
      </ul>
    </Section>
    <Section title="批量导入 CSV">
      列：<code>code,name,energyTypeCode,orgNodeCode,channelCode,scale,valueKind,enabled,remark</code>
      。 失败会按行号返回原因；不会"半成功"——校验失败整批回滚。
    </Section>
    <Section title="删除前注意">
      <span style={{ color: '#ff4d4f' }}>
        删除会同时清掉 InfluxDB 中该 meter_code 的历史曲线（不可恢复）
      </span>
      。 一般做法是禁用（enabled=false）而非删除。
    </Section>
  </div>
);

export const HELP_COLLECTOR: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      采集器（collector）通道管理。每个通道 = 一条到现场设备的链路（Modbus TCP / Modbus RTU /
      OPC UA / MQTT 等）。
    </Section>
    <Section title="状态">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>RUNNING</b>：通道在跑，最近一次轮询有响应
        </li>
        <li>
          <b>FAILED</b>：连续失败超过阈值；查 hover 错误（拒绝连接 / CRC 错 / 超时 / ...）
        </li>
        <li>
          <b>STOPPED</b>：人为停用
        </li>
      </ul>
    </Section>
    <Section title="新建通道字段">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>transport</b>：MODBUS_TCP / MODBUS_RTU / OPCUA / MQTT
        </li>
        <li>
          MODBUS_TCP：host + port + slaveId；间隔单位毫秒
        </li>
        <li>MODBUS_RTU：串口设备（/dev/ttyUSB0）+ 波特率 + 校验位</li>
        <li>OPC UA：endpointUrl + 认证（用户名密码 / 证书）；首次会触发证书审批</li>
      </ul>
    </Section>
    <Section title="证书审批（OPC UA）">
      OPC UA 客户端首次连接时把自身证书 push 给服务端待信任；同时也会拉服务端证书。 待信任的对端证书在「系统管理
      → 证书审批」批准。<b>批准前 OPC UA 连接会一直 FAILED</b>。
    </Section>
    <Section title="轮询间隔">
      建议 5000ms（5s）。低于 1000ms 多数表计 ACK 不来；高于 60000ms 会让「静默超时」误报，
      请同步调高报警规则。
    </Section>
    <Section title="改通道后什么时候生效">
      保存后<b>下一个轮询周期</b>立刻生效（最长 5s）。无需重启 ems-app。
    </Section>
  </div>
);

export const HELP_CERT_APPROVAL: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      OPC UA 证书审批。collector 与 OPC UA 服务器首次握手时， 双方互相 push 证书；本页列出<b>待信任的对端证书</b>。
    </Section>
    <Section title="字段">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>thumbprint</b>：证书 SHA-1 指纹，唯一标识
        </li>
        <li>
          <b>subject / issuer</b>：颁发者与持有者 DN
        </li>
        <li>
          <b>notBefore / notAfter</b>：有效期；过期证书<b>不要批准</b>
        </li>
      </ul>
    </Section>
    <Section title="操作">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>批准</b>：写入 trustlist；该 thumbprint 的后续连接直接放行
        </li>
        <li>
          <b>拒绝</b>：从 pending 列表移除；下次握手会再次出现
        </li>
        <li>
          <span style={{ color: '#faad14' }}>
            生产环境务必核对 issuer 与 thumbprint，避免错信中间人证书
          </span>
        </li>
      </ul>
    </Section>
    <Section title="自动刷新">
      列表每 10 秒自动拉取一次；批准后不需要手动刷。
    </Section>
  </div>
);

export const HELP_USERS: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      系统用户管理。包含创建/禁用/重置密码/分配角色。
    </Section>
    <Section title="字段">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>username</b>：登录名，唯一不可改
        </li>
        <li>
          <b>角色</b>：决定全局权限（菜单/按钮）；ADMIN 拥有一切
        </li>
        <li>
          <b>enabled</b>：禁用后不能登录但保留审计记录
        </li>
        <li>
          <b>组织权限</b>：在「用户权限」页单独管，控制可见的 org 子树
        </li>
      </ul>
    </Section>
    <Section title="重置密码">
      管理员重置后会生成临时密码；用户首次登录强制修改。 重置事件会写审计日志。
    </Section>
    <Section title="删除 vs 禁用">
      <span style={{ color: '#ff4d4f' }}>删除会丢失审计可追溯性</span>
      ， 一般用「禁用」。删除仅在测试账号或合规要求时使用。
    </Section>
  </div>
);

export const HELP_USER_EDIT: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      编辑单个用户的姓名、邮箱、角色与启用状态。
    </Section>
    <Section title="角色 vs 权限">
      角色（ADMIN / OPERATOR / VIEWER）决定<b>功能权限</b>（菜单和按钮）；
      组织权限（在「用户权限」页）决定<b>数据范围</b>（看哪些 org 子树）。 两者独立校验，AND 关系。
    </Section>
    <Section title="username 不可改">
      改用户名会破坏审计追溯链；如需改请删用户重建并保留旧账号禁用。
    </Section>
    <Section title="保存生效">
      立即生效。被编辑用户的<b>下一次请求</b>就会按新角色/状态校验； 已签发的 JWT 不会主动失效，但权限校验走 DB。
    </Section>
  </div>
);

export const HELP_USER_PERMISSIONS: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      给用户授权可见的<b>组织节点</b>。决定他在表计、报表、账单等所有按 org 过滤的页面能看到什么。
    </Section>
    <Section title="scope 范围">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>SUBTREE 子树</b>：该节点及其所有后代
        </li>
        <li>
          <b>NODE_ONLY 仅本节点</b>：只这一个节点，不含子
        </li>
      </ul>
      多条授权取并集。
    </Section>
    <Section title="ADMIN 例外">
      ADMIN 角色无视组织权限，能看全部。 给 ADMIN 加授权毫无意义但不会报错（系统忽略）。
    </Section>
    <Section title="授权撤销">
      撤销立即生效；用户下一次请求会被拒。<b>已下载的报表 / 已导出的 CSV 不会回收</b>， 如需保密请同时旋转密码。
    </Section>
    <Section title="审计">
      所有授权/撤销都写入审计日志（resource_type=NODE_PERMISSION）。
    </Section>
  </div>
);

export const HELP_ROLES: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      角色列表（只读）。当前版本角色为<b>预定义</b>，不支持新建/修改。
    </Section>
    <Section title="预定义角色">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>ADMIN</b>：全部权限，含锁账期/解锁/删用户/审计访问
        </li>
        <li>
          <b>OPERATOR</b>：常规运营（产量录入、关账期、新建规则、ACK 报警）
        </li>
        <li>
          <b>VIEWER</b>：只读，不可写任何业务数据
        </li>
      </ul>
    </Section>
    <Section title="为什么不可改">
      角色 → 权限的映射写死在后端代码以减少越权风险；如需新角色请提产品需求。
    </Section>
  </div>
);

export const HELP_AUDIT_LOG: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      系统审计日志。所有<b>写操作</b>（登录/登出/CRUD/配置变更/锁解锁）都会记一条。
    </Section>
    <Section title="字段">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>action</b>：LOGIN / LOGOUT / LOGIN_FAIL / CREATE / UPDATE / DELETE / MOVE / CONFIG_CHANGE
        </li>
        <li>
          <b>resourceType</b>：被操作对象（USER / ORG_NODE / NODE_PERMISSION / ...）
        </li>
        <li>
          <b>resourceId</b>：对应实体主键
        </li>
        <li>
          <b>actor</b>：操作人 username
        </li>
        <li>
          <b>ip / userAgent</b>：客户端信息（用于异常登录排查）
        </li>
        <li>
          <b>diff</b>：变更前后 JSON（点详情查看）
        </li>
      </ul>
    </Section>
    <Section title="保留期">
      默认 365 天；合规场景请配置外部归档。日志只读，不能修改/删除。
    </Section>
    <Section title="查询建议">
      时间窗 + actor 联合过滤最快；按 resourceType 过滤可定位对象级历史。 跨年大查询请走数据库直查。
    </Section>
  </div>
);

export const HELP_PROFILE: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      个人中心：当前账号修改密码。
    </Section>
    <Section title="密码策略">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>长度 ≥ 8</li>
        <li>建议大小写 + 数字 + 符号混合</li>
        <li>不可与最近 3 次密码相同（如启用历史校验）</li>
      </ul>
    </Section>
    <Section title="改密后">
      其他客户端的 JWT 仍在有效期内（默认 8 小时）依然可用； 如怀疑泄露请联系管理员强制下线（旋转 secret）。
    </Section>
    <Section title="忘记旧密码">
      联系管理员在「用户管理」重置；不会要求邮箱验证（默认无邮件服务）。
    </Section>
  </div>
);

export const HELP_PRODUCTION_SHIFTS: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      班次定义。每条 = 一个班（早 / 中 / 晚 / 大夜...）+ 起止时刻（HH:mm）。 「日产量录入」按班次拆分，
      「班次报表」按班次聚合。
    </Section>
    <Section title="跨零点班次">
      timeStart &gt; timeEnd 表示<b>跨零点</b>（如 22:00–06:00）。 系统识别后自动归到 timeStart 当日，
      避免「20:00 上的班归错日」。
    </Section>
    <Section title="enabled 关掉后">
      产量录入/班报表中不可选；历史已录入的数据保留并可导出。
    </Section>
    <Section title="字段">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>code</b>：唯一编码（A、B、C 或 EARLY/LATE/NIGHT）
        </li>
        <li>
          <b>name</b>：展示名
        </li>
        <li>
          <b>顺序</b>：决定下拉/报表中的排序
        </li>
      </ul>
    </Section>
    <Section title="改时段会发生什么">
      <span style={{ color: '#faad14' }}>
        改完后过去的产量录入仍按当时班次保留；新录入按新时段
      </span>
      ；不会回溯历史报表。
    </Section>
  </div>
);

export const HELP_FLOORPLAN_LIST: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      设备分布图（floorplan）列表。每个分布图 = 一张底图（车间平面图）+ 上面打点的<b>测点</b>。
    </Section>
    <Section title="用途">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>大屏轮播展示</li>
        <li>巡检按图找位置</li>
        <li>报警时可视化定位</li>
      </ul>
    </Section>
    <Section title="底图建议">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          PNG / JPG，宽度 1200-2400px；&gt; 5MB 上传会拒绝
        </li>
        <li>建议导出 CAD / Visio 时勾「白底」、关闭网格</li>
        <li>底图替换会保留打点坐标（按比例换算）</li>
      </ul>
    </Section>
    <Section title="组织绑定">
      orgNodeId 决定权限：用户没该节点权限 = 看不到这张图。
    </Section>
  </div>
);

export const HELP_FLOORPLAN_EDITOR: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      在底图上为<b>表计</b>打点。点击底图新增点位，拖动点位调整位置，右侧表格可改名/删点。
    </Section>
    <Section title="操作">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>左键空白处</b>：新增一个点（先选好右侧的表计）
        </li>
        <li>
          <b>拖动圆点</b>：调整位置；坐标 (x, y) 是<b>相对底图归一化</b>到 [0,1]
        </li>
        <li>
          <b>右侧表格</b>：删除、改 label、关联其它 meter
        </li>
        <li>
          <b>保存</b>后大屏轮播立刻刷新（最长 30 秒）
        </li>
      </ul>
    </Section>
    <Section title="点位上限">
      单图 200 点为软上限；过多会让大屏渲染卡顿，建议拆图。
    </Section>
    <Section title="坐标稳定性">
      底图换图后坐标按宽高比缩放，不会"飞"；但如果新图裁切了原区域，超出的点会保留在边界。
    </Section>
  </div>
);

export const HELP_BILL_DETAIL: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      单张账单的明细：(账期, 组织, 能源) 的合计 + 该账单引用的<b>分摊行</b>列表。
    </Section>
    <Section title="头部信息">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>金额合计</b> = 用量 × 时段电价；分尖/峰/平/谷四档显示
        </li>
        <li>
          <b>产量与单位成本</b>：产量来自「日产量录入」；单位成本 = 金额 / 产量
        </li>
        <li>
          <b>关联批次</b>：账单生成时引用的 cost run id；点击可回溯
        </li>
      </ul>
    </Section>
    <Section title="明细行">
      明细行 = 该账单贡献的每条分摊结果，列出主表 / 时段 / kWh / 金额。 用于核对账单与现场数据是否一致。
    </Section>
    <Section title="为什么金额对不上">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>分摊批次后又改了规则/产量 → 重生成账单覆盖</li>
        <li>电价方案当月改过 → 看「电价方案」生效区间</li>
        <li>四舍五入：每条明细独立四舍五入再求和，可能与"合计后舍入"差 1-2 分</li>
      </ul>
    </Section>
  </div>
);

export const HELP_COST_RUN_DETAIL: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      单次分摊批次（cost run）的明细：状态、参数、生成的<b>每行分摊结果</b>。
    </Section>
    <Section title="头部">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>status</b>：PENDING / RUNNING / SUCCESS / FAILED / SUPERSEDED
        </li>
        <li>
          <b>periodStart / periodEnd</b>：批次涵盖的半开区间
        </li>
        <li>
          <b>用规则</b>：实际选用的规则 id 列表
        </li>
        <li>
          <b>errorMessage</b>：FAILED 时排查入口
        </li>
      </ul>
    </Section>
    <Section title="明细列">
      meter / 主表 / 算法 / targetOrgId / 能源 / kWh / 金额。 多个规则的输出全部合并展示，可按 org / 能源
      过滤。
    </Section>
    <Section title="SUPERSEDED">
      同 period 又跑一次新 run，旧 run 自动 SUPERSEDED。 账期关账只用最新 SUCCESS run；旧的留作审计。
    </Section>
    <Section title="重跑">
      不在本页操作；回「分摊批次」新建即可。FAILED 的 run<b>不会</b>自动续跑。
    </Section>
  </div>
);

export const HELP_REPORT_DAILY: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      日报 = 选定日期 × 选定 org 子树 × 各能源类型 ×
      24 小时矩阵。固定模板，列固定。
    </Section>
    <Section title="过滤">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>日期</b>：本地时区当日 00:00-24:00
        </li>
        <li>
          <b>组织</b>：选父节点 = 子树合计；不选 = 全厂
        </li>
        <li>
          <b>能源</b>：留空 = 全部
        </li>
      </ul>
    </Section>
    <Section title="导出格式">
      支持 CSV / EXCEL（含格式样式）。大数据量请用「即席查询 + 异步」。
    </Section>
    <Section title="单位">
      电 kWh、水 m³、气 m³、汽 t、油 L；矩阵单元格右下角小字标注。
    </Section>
    <Section title="为什么和实时面板不一致">
      日报数据是<b>聚合后</b>的 hour bucket，与实时（5s 轮询）有 1-3 分钟延迟。
    </Section>
  </div>
);

export const HELP_REPORT_MONTHLY: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      月报：选定月份的 org × 能源 × 31 日 矩阵。 适合月底交报、与上月对比。
    </Section>
    <Section title="数据口径">
      数据按<b>自然日</b>切分；表计 valueKind 不影响展示（引擎自动适配）。 月底当天的 23:59:59 数据计入当月。
    </Section>
    <Section title="导出">
      CSV / EXCEL 同步下载；超大子树请改异步导出。
    </Section>
    <Section title="跨月规则">
      月初 00:00:00 起算，月末最后一秒 23:59:59 止；闰年 2 月按实际天数。
    </Section>
  </div>
);

export const HELP_REPORT_YEARLY: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      年报：选定年份的 org × 能源 × 12 月 矩阵。适合年度复盘、对外披露。
    </Section>
    <Section title="数据来源">
      与日报/月报同一份时序库，仅聚合粒度不同。年报 row = MONTH 桶。
    </Section>
    <Section title="导出">
      CSV / EXCEL。 跨多年请用「即席查询 + 自定义粒度」。
    </Section>
  </div>
);

export const HELP_REPORT_SHIFT: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      班次报表：选定日期 × 班次 × org × 能源。每个班的能耗与产量并列。
    </Section>
    <Section title="班次配置">
      班次定义在「班次管理」。改了班次时段不会回溯历史。
    </Section>
    <Section title="跨零点班次">
      跨零点班归到「上班开始日」。例：22:00 上班、06:00 下班，归 22:00 当日。
    </Section>
    <Section title="为什么有的班是空">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>该日没排该班 → 正常空</li>
        <li>产量未录入 → 单位能耗显示 —</li>
        <li>表计当天离线 → 能耗显示 —，看「报警 → 健康」排查</li>
      </ul>
    </Section>
  </div>
);

export const HELP_REPORT_EXPORT: ReactNode = (
  <div style={baseStyle}>
    <Section title="这是什么">
      <b>预设报表</b>的异步导出入口。和「即席查询」不同：这里走<b>固定模板</b>（日报/月报/年报/班报/COST_MONTHLY），
      仅时间口径和过滤项可调。
    </Section>
    <Section title="选择 preset">
      <ul style={{ paddingLeft: 16, margin: 0 }}>
        <li>
          <b>DAILY</b>：单日；填日期
        </li>
        <li>
          <b>MONTHLY</b>：单月；填年月
        </li>
        <li>
          <b>YEARLY</b>：单年；填年
        </li>
        <li>
          <b>SHIFT</b>：单日 + 单班次
        </li>
        <li>
          <b>COST_MONTHLY</b>：账单口径（用量 × 电价 × 产量），与「账单列表」导出一致
        </li>
      </ul>
    </Section>
    <Section title="format 格式">
      CSV / EXCEL。EXCEL 含分组、表头、合计行；CSV 适合二次入库。
    </Section>
    <Section title="任务流程">
      <ol style={{ paddingLeft: 18, margin: 0 }}>
        <li>提交 → 拿到 token</li>
        <li>页面自动轮询（每 1.5 秒、最多 80 次 ≈ 2 分钟）</li>
        <li>就绪后浏览器直接弹下载</li>
      </ol>
    </Section>
    <Section title="超时怎么办">
      2 分钟内未就绪会提示「导出超时」。token 仍有效（30 分钟内）， 可去「即席查询」页底部异步任务列表手动下载。
    </Section>
  </div>
);
