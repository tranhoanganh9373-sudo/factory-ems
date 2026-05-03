# 采集器协议扩展设计（OPC UA + MQTT + VIRTUAL）

**日期：** 2026-04-30
**作者：** EMS 团队
**状态：** Draft（待评审）
**关联模块：** `ems-collector`、`ems-app`、`frontend`

---

## 1. 背景与目标

### 1.1 现状

`ems-collector` 当前仅支持 Modbus TCP / RTU 两种协议，配置以静态 YAML 形式分散在各 deployment 中。前端"测点管理"页只能维护 Modbus 寄存器映射，缺乏对其他工业协议的支持。

### 1.2 目标

为 EMS 增加 **OPC UA**、**MQTT** 两种主流工业协议接入能力，并新增 **VIRTUAL** 模拟协议（为未连接真实仪表的节点提供合成数据），同时引入：

- 跨协议统一抽象（Channel / Transport / Sample）
- 数据库动态配置（取代静态 YAML）
- 前端按协议条件渲染的配置表单
- 文件系统密钥管理 + OPC UA 证书审批流
- 统一诊断与可观测性

### 1.3 Out of Scope

- HA / 集群部署
- DLT645 / IEC104 / BACnet 等其他协议
- OPC UA 多证书轮换
- 历史指标长期存储（>30 天）

---

## 2. 用户决策摘要（11 项）

| 编号 | 决策 | 选项 |
|---|---|---|
| Q1 | 一次性引入 3 种协议（OPC UA / MQTT / VIRTUAL） | a |
| Q2 | 配置存储：DB 动态配置 | b |
| Q3 | 配置 schema：JSONB + sealed interface | a |
| Q4 | 数据流：混合 PULL + PUSH | c |
| Q5 | VIRTUAL 模式：constant + sine + random_walk + calendar_curve | b |
| Q6 | OPC UA 安全模式：None / Sign / Sign+Encrypt 全支持 | a+b+c |
| Q7 | MQTT payload：JSONPath 提取 | c |
| Q8 | 凭据存储：文件系统 + secret://ref 引用 | c |
| Q9 | 前端表单：按协议条件渲染 | b |
| Q10 | 测试：Testcontainers + mosquitto + Eclipse Milo | b |
| Q11 | 诊断：标准 Spring Actuator + Micrometer | b |

---

## 3. 架构（Section 1/8）

### 3.1 模块边界

```
ems-collector/
├── transport/                    # 协议抽象层
│   ├── Transport.java            # sealed interface
│   ├── ChannelRegistry.java      # 全局 channel 注册表
│   └── impl/
│       ├── ModbusTcpTransport.java
│       ├── ModbusRtuTransport.java
│       ├── OpcUaTransport.java
│       ├── MqttTransport.java
│       └── VirtualTransport.java
├── config/                       # JSONB 配置反序列化
│   ├── ChannelConfig.java        # sealed interface
│   └── impl/                     # 各 protocol 的 record
├── secret/                       # 凭据解析
│   └── FilesystemSecretResolver.java
├── runtime/                      # 运行时状态
│   ├── ChannelStateRegistry.java
│   ├── ChannelRuntimeState.java
│   └── HourlyCounter.java
└── service/
    ├── CollectorService.java     # 协调启停、订阅、数据写入
    └── ChannelDiagnosticsService.java
```

### 3.2 数据流

```
PULL 协议（Modbus, OPC UA Read, VIRTUAL）：
  Scheduler ─tick─▶ Transport.read() ─▶ Sample ─▶ MeasurementWriter ─▶ DB
                                                     └─▶ ChannelStateRegistry.recordSuccess()

PUSH 协议（OPC UA Subscribe, MQTT）：
  Broker/Server ─event─▶ Transport.callback ─▶ Sample ─▶ MeasurementWriter ─▶ DB
                                                            └─▶ WebSocket ─▶ frontend
```

### 3.3 技术栈

| 协议 | 库 | 版本 |
|---|---|---|
| Modbus | digitalpetri/modbus | 2.x（既有） |
| OPC UA | Eclipse Milo | 0.6.x（兼容 Spring Boot 3.3 + Java 21） |
| MQTT | Eclipse Paho | 1.2.5 |
| JSONPath | Jayway JsonPath | 2.9 |

---

## 4. 数据模型（Section 2/8）

### 4.1 表结构

```sql
-- V20260501__channel_table.sql
CREATE TABLE channel (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    protocol        VARCHAR(32)  NOT NULL,           -- MODBUS_TCP / MODBUS_RTU / OPC_UA / MQTT / VIRTUAL
    enabled         BOOLEAN NOT NULL DEFAULT true,
    is_virtual      BOOLEAN NOT NULL DEFAULT false,  -- VIRTUAL 协议标记，用于过滤报警/对账
    protocol_config JSONB NOT NULL,                  -- sealed interface 序列化结果
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_channel_protocol ON channel(protocol);
CREATE INDEX idx_channel_enabled  ON channel(enabled) WHERE enabled = true;

-- 现有 meter 表添加外键，nullable 兼容旧数据
ALTER TABLE meter ADD COLUMN channel_id BIGINT REFERENCES channel(id);

-- 诊断指标聚合表（每分钟 flush 一次）
CREATE TABLE collector_metrics (
    channel_id   BIGINT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    bucket_at    TIMESTAMPTZ NOT NULL,                -- truncate to minute
    success_cnt  INTEGER NOT NULL DEFAULT 0,
    failure_cnt  INTEGER NOT NULL DEFAULT 0,
    avg_latency_ms INTEGER,
    PRIMARY KEY (channel_id, bucket_at)
);

CREATE INDEX idx_collector_metrics_bucket ON collector_metrics(bucket_at);
```

`collector_metrics` 仅保留 30 天，定期由 `pg_cron` 或应用层任务清理。

### 4.2 ChannelConfig sealed interface

```java
@JsonTypeInfo(use = Id.NAME, property = "protocol")
@JsonSubTypes({
    @Type(value = ModbusTcpConfig.class,  name = "MODBUS_TCP"),
    @Type(value = ModbusRtuConfig.class,  name = "MODBUS_RTU"),
    @Type(value = OpcUaConfig.class,      name = "OPC_UA"),
    @Type(value = MqttConfig.class,       name = "MQTT"),
    @Type(value = VirtualConfig.class,    name = "VIRTUAL")
})
public sealed interface ChannelConfig
    permits ModbusTcpConfig, ModbusRtuConfig, OpcUaConfig, MqttConfig, VirtualConfig {

    String protocol();
    Duration pollInterval();   // PULL 协议使用；PUSH 返回 null
    List<? extends PointConfig> points();
}
```

各协议 record：

```java
public record OpcUaConfig(
    String endpointUrl,                      // opc.tcp://host:port/path
    SecurityMode securityMode,               // NONE / SIGN / SIGN_AND_ENCRYPT
    String certRef,                          // secret://opcua/plc-line1.pfx (None 模式可空)
    String certPasswordRef,
    String usernameRef,                      // 可空
    String passwordRef,
    Duration pollInterval,                   // null = 仅订阅模式
    List<OpcUaPoint> points
) implements ChannelConfig { ... }

public record OpcUaPoint(
    String key,                              // 业务 key
    String nodeId,                           // ns=2;s=Channel1.Device1.Tag1
    SubscriptionMode mode,                   // SUBSCRIBE / READ
    Double samplingIntervalMs,               // SUBSCRIBE 模式
    String unit
) implements PointConfig {}

public record MqttConfig(
    String brokerUrl,                        // tcp://broker:1883 / ssl://broker:8883
    String clientId,
    String usernameRef,                      // 可空
    String passwordRef,
    String tlsCaCertRef,                     // 可空（无 TLS 时）
    int qos,                                 // 0 / 1
    boolean cleanSession,
    Duration keepAlive,
    List<MqttPoint> points
) implements ChannelConfig {
    public Duration pollInterval() { return null; }
}

public record MqttPoint(
    String key,
    String topic,                            // sensors/factory1/+/temperature
    String jsonPath,                         // $.value 或 $.payload.temp
    String unit,
    String timestampJsonPath                 // 可空，缺省用接收时间
) implements PointConfig {}

public record VirtualConfig(
    Duration pollInterval,                   // 默认 1s
    List<VirtualPoint> points
) implements ChannelConfig {}

public record VirtualPoint(
    String key,
    VirtualMode mode,                        // CONSTANT / SINE / RANDOM_WALK / CALENDAR_CURVE
    Map<String, Double> params,              // 各模式所需参数
    String unit
) implements PointConfig {}
```

### 4.3 JSONB 校验

后端：Spring Bean Validation（`@NotBlank`, `@NotNull`, `@Min`, `@Pattern`）+ 自定义 `@ValidChannelConfig` 跨字段校验（如 OPC UA SIGN 模式必须有 certRef）。

前端：Zod schema 与后端一一对应。

---

## 5. Transport 抽象（Section 3/8）

### 5.1 接口

```java
public sealed interface Transport
    permits ModbusTcpTransport, ModbusRtuTransport, OpcUaTransport, MqttTransport, VirtualTransport {

    void start(ChannelConfig config, SampleSink sink) throws TransportException;
    void stop();
    boolean isConnected();
    TestResult testConnection(ChannelConfig config);  // 同步测试，10s 超时
}

@FunctionalInterface
public interface SampleSink {
    void accept(Sample sample);
}

public record Sample(
    Long channelId,
    String pointKey,
    Instant timestamp,
    Object value,                            // Number / Boolean / String
    Quality quality,                         // GOOD / UNCERTAIN / BAD
    Map<String, String> tags                 // 可选元数据：source、subscriptionId 等
) {}
```

### 5.2 生命周期

`CollectorService` 启动时：
1. 从 `channel` 表加载所有 `enabled=true` 的 channel
2. 对每个 channel：调用 `secretResolver.resolve()` 注入凭据 → 实例化对应 Transport → `start()`
3. 失败的 channel 记录到 `ChannelStateRegistry`，状态 = `ERROR`，不影响其他 channel 启动

支持运行时增删改：
- `POST /api/v1/channel` → 创建并启动
- `PUT /api/v1/channel/{id}` → 停止旧实例 + 启动新实例
- `DELETE /api/v1/channel/{id}` → 停止并移除

### 5.3 错误处理与重连

每个 Transport 实现内置重连策略：
- 指数退避：1s → 2s → 4s → ... → 60s（封顶）
- 抖动：±20%
- 最大连续失败次数无上限（持续重试），但每次失败上报 `ChannelStateRegistry`

---

## 6. 协议实现（Section 4/8）

### 6.1 ModbusTcpTransport / ModbusRtuTransport

继承现有实现，仅适配新的 `Transport` 接口。无功能变化。

### 6.2 OpcUaTransport

基于 Eclipse Milo `OpcUaClient`：

```java
class OpcUaTransport implements Transport {
    private OpcUaClient client;
    private UaSubscription subscription;

    public void start(ChannelConfig config, SampleSink sink) {
        var cfg = (OpcUaConfig) config;
        var clientConfig = OpcUaClientConfig.builder()
            .setApplicationName(LocalizedText.english("EMS Collector"))
            .setApplicationUri("urn:ems:collector")
            .setEndpoint(selectEndpoint(cfg))
            .setKeyPair(loadKeyPair(cfg))            // SIGN/ENCRYPT 模式
            .setCertificate(loadCert(cfg))
            .setIdentityProvider(buildIdentity(cfg)) // 用户名密码 / 匿名
            .setRequestTimeout(uint(10_000))
            .build();

        client = OpcUaClient.create(clientConfig);
        client.connect().get(10, SECONDS);

        // 订阅模式
        var subPoints = cfg.points().stream().filter(p -> p.mode() == SUBSCRIBE).toList();
        if (!subPoints.isEmpty()) {
            subscription = client.getSubscriptionManager().createSubscription(1000.0).get();
            var items = subPoints.stream().map(this::buildMonitoredItem).toList();
            subscription.createMonitoredItems(TimestampsToReturn.Both, items, (item, idx) -> {
                item.setValueConsumer((it, value) -> {
                    sink.accept(toSample(it, value));
                });
            }).get();
        }
        // 轮询模式：交给 Scheduler 调用 read()
    }

    public TestResult testConnection(ChannelConfig config) {
        // 临时 client，只发 GetEndpoints + Connect，不订阅
    }
}
```

证书首次连接时若不在 TrustStore：
- 拒绝连接
- 记录指纹到 `ChannelStateRegistry.lastErrorMessage = "Untrusted server certificate: SHA-256: xx:xx:..."`
- 触发报警 `OPC_UA_CERT_PENDING`
- 管理员通过 `POST /api/v1/collector/{id}/trust-cert { thumbprint }` 批准

### 6.3 MqttTransport

```java
class MqttTransport implements Transport {
    private MqttAsyncClient client;
    private final Configuration jsonPathConfig = Configuration.defaultConfiguration()
        .addOptions(Option.SUPPRESS_EXCEPTIONS);

    public void start(ChannelConfig config, SampleSink sink) {
        var cfg = (MqttConfig) config;
        var opts = new MqttConnectOptions();
        opts.setCleanSession(cfg.cleanSession());
        opts.setKeepAliveInterval((int) cfg.keepAlive().toSeconds());
        if (cfg.usernameRef() != null) {
            opts.setUserName(secretResolver.resolve(cfg.usernameRef()));
            opts.setPassword(secretResolver.resolve(cfg.passwordRef()).toCharArray());
        }
        if (cfg.brokerUrl().startsWith("ssl://")) {
            opts.setSocketFactory(buildTlsSocketFactory(cfg.tlsCaCertRef()));
        }

        client = new MqttAsyncClient(cfg.brokerUrl(), cfg.clientId(), new MemoryPersistence());
        client.setCallback(new MqttCallbackImpl(cfg, sink));
        client.connect(opts).waitForCompletion(10_000);

        // 批量订阅
        var topics = cfg.points().stream().map(MqttPoint::topic).distinct().toArray(String[]::new);
        var qos = new int[topics.length]; Arrays.fill(qos, cfg.qos());
        client.subscribe(topics, qos).waitForCompletion(10_000);
    }

    private void handleMessage(String topic, MqttMessage msg, MqttConfig cfg, SampleSink sink) {
        var payload = new String(msg.getPayload(), UTF_8);
        var doc = JsonPath.using(jsonPathConfig).parse(payload);
        for (var point : cfg.points()) {
            if (!topicMatches(point.topic(), topic)) continue;
            var value = doc.read(point.jsonPath(), Object.class);
            if (value == null) continue;  // JSONPath 未匹配，跳过
            var ts = point.timestampJsonPath() != null
                ? Instant.parse(doc.read(point.timestampJsonPath(), String.class))
                : Instant.now();
            sink.accept(new Sample(channelId, point.key(), ts, value, GOOD, Map.of("topic", topic)));
        }
    }
}
```

Topic 通配匹配支持 `+` 单层 + `#` 多层（标准 MQTT）。

### 6.4 VirtualTransport

```java
class VirtualTransport implements Transport {
    private ScheduledExecutorService scheduler;

    public void start(ChannelConfig config, SampleSink sink) {
        var cfg = (VirtualConfig) config;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> tick(cfg, sink),
            0, cfg.pollInterval().toMillis(), MILLISECONDS);
    }

    private void tick(VirtualConfig cfg, SampleSink sink) {
        var now = Instant.now();
        for (var point : cfg.points()) {
            var value = generate(point, now);
            sink.accept(new Sample(channelId, point.key(), now, value, GOOD,
                Map.of("virtual", "true")));
        }
    }

    private Object generate(VirtualPoint p, Instant now) {
        return switch (p.mode()) {
            case CONSTANT -> p.params().get("value");
            case SINE -> {
                double amp = p.params().get("amplitude");
                double period = p.params().get("periodSec");
                double offset = p.params().getOrDefault("offset", 0.0);
                double t = (now.toEpochMilli() / 1000.0) % period;
                yield amp * Math.sin(2 * Math.PI * t / period) + offset;
            }
            case RANDOM_WALK -> randomWalk(p);  // 维护 per-point 状态
            case CALENDAR_CURVE -> calendarCurve(p, now);  // 工作日 vs 周末曲线
        };
    }
}
```

Sample 的 `tags` 强制带 `virtual=true` 标识，下游报警/对账逻辑过滤。

---

## 7. 前端配置 UI（Section 5/8）

### 7.1 测点管理页扩展

`/meters` 页升级为两栏布局：
- 左栏：channel 列表（按协议分类，带状态指示）
- 右栏：选中 channel 的详情 + 编辑表单

新增按钮："新增通道" → 弹出 ChannelEditor Drawer。

### 7.2 ChannelEditor 结构

```tsx
function ChannelEditor({ channel, onSave }: Props) {
  const [protocol, setProtocol] = useState(channel?.protocol ?? 'MODBUS_TCP');

  return (
    <Form layout="vertical" form={form}>
      <Form.Item name="name" label="通道名称" rules={[{ required: true, max: 128 }]}>
        <Input />
      </Form.Item>
      <Form.Item name="protocol" label="协议">
        <Select options={PROTOCOLS} disabled={!!channel} onChange={setProtocol} />
      </Form.Item>

      {protocol === 'MODBUS_TCP' && <ModbusTcpForm />}
      {protocol === 'MODBUS_RTU' && <ModbusRtuForm />}
      {protocol === 'OPC_UA'     && <OpcUaForm />}
      {protocol === 'MQTT'       && <MqttForm />}
      {protocol === 'VIRTUAL'    && <VirtualForm />}

      <Space>
        <Button onClick={handleTest}>测试连接</Button>
        <Button type="primary" onClick={onSave}>保存</Button>
      </Space>
    </Form>
  );
}
```

### 7.3 凭据字段处理

凭据字段统一组件 `<SecretInput>`：
- 显示 placeholder `secret://...` 表示已配置
- 点击"修改"按钮 → 弹 Modal → 输入明文 → `POST /api/v1/secrets` → 回填 `secret://xxx`
- 永远不回显明文

OPC UA 证书：
- 上传 `.pfx` 文件 + PFX 密码 → `multipart/form-data` 到 `/api/v1/secrets/opcua/cert`
- 后端落到 `~/.ems/secrets/opcua/certs/{channelId}.pfx` mode 600

### 7.4 i18n 字典扩展

```typescript
// utils/i18n-dict.ts
export const COLLECTOR_PROTOCOL_LABEL = {
  MODBUS_TCP: 'Modbus TCP',
  MODBUS_RTU: 'Modbus RTU',
  OPC_UA: 'OPC UA',
  MQTT: 'MQTT',
  VIRTUAL: '虚拟（模拟）',
} as const;

export const VIRTUAL_MODE_LABEL = {
  CONSTANT: '恒定值',
  SINE: '正弦波',
  RANDOM_WALK: '随机游走',
  CALENDAR_CURVE: '日历曲线',
} as const;

export const OPCUA_SECURITY_MODE_LABEL = {
  NONE: '无安全',
  SIGN: '仅签名',
  SIGN_AND_ENCRYPT: '签名 + 加密',
} as const;
```

### 7.5 工作量

约 5-6 天（含表单组件 + 测试连接交互 + i18n + 单元测试）。

---

## 8. 凭据 / 安全管理（Section 6/8）

### 8.1 文件系统布局

```
~/.ems/secrets/                    (mode 700, owner: ems)
├── mqtt/
│   ├── broker-prod.password       (mode 600)
│   └── broker-staging.password
├── opcua/
│   ├── plc-line1.username
│   ├── plc-line1.password
│   ├── <name>.pem                  # 客户端证书 + encrypted PKCS#8 私钥（由 .pfx 上传转换而成）
│   ├── <name>.pem.password         # 上述 PEM 私钥的解密密码
│   └── certs/
│       └── trusted/               # 受信任服务器证书白名单 (DER)
│           ├── plc-line1.der
│           └── plc-line2.der
└── modbus/                        # 预留
```

环境变量 `EMS_SECRETS_DIR` 覆盖默认路径。

### 8.2 SecretResolver 接口

```java
public interface SecretResolver {
    String resolve(String ref);                      // "secret://mqtt/broker-prod.password" → 明文
    boolean exists(String ref);
    void write(String ref, String value);            // 仅 ADMIN
    void delete(String ref);
}

@Component
class FilesystemSecretResolver implements SecretResolver {
    @Value("${ems.secrets.dir}")
    private Path secretsDir;

    public String resolve(String ref) {
        var path = parseAndValidate(ref);             // 防路径遍历
        return Files.readString(path).strip();
    }

    public void write(String ref, String value) {
        var path = parseAndValidate(ref);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
        Files.setPosixFilePermissions(path, Set.of(OWNER_READ, OWNER_WRITE));
        auditLog.record("SECRET_WRITE", ref);
    }

    private Path parseAndValidate(String ref) {
        if (!ref.startsWith("secret://")) throw new IllegalArgumentException();
        var rel = ref.substring("secret://".length());
        var path = secretsDir.resolve(rel).normalize();
        if (!path.startsWith(secretsDir)) throw new SecurityException("Path traversal");
        return path;
    }
}
```

### 8.3 OPC UA 证书审批流（已实现）

1. 客户端连接服务器 → `OpcUaTransport.buildCertificateValidator` 拉取服务器证书指纹（`HexFormat` 小写无分隔）
2. 校验 `~/.ems/secrets/opcua/certs/trusted/` 中是否存在
3. 不存在：
   - 调用 `OpcUaCertificateStore.addPending(cert, channelId, endpointUrl)` 把 `.der` + `.json` 元数据写入 `pending/`（POSIX `rw-------`，幂等）
   - 发布 `ChannelCertificatePendingEvent` → `CertificatePendingListener` 同步创建 `OPC_UA_CERT_PENDING` 报警（同 channel 同时只有一条 ACTIVE）
   - 抛出 `TransportException` 拒绝连接
4. 管理员在 `/admin/cert-approval` 页面（`<PageHeader title="证书审批" />`）查看待审批证书列表
   - 后端 `GET /api/v1/collector/cert-pending` 列出 `PendingCertificate(thumbprint, channelId, endpointUrl, firstSeenAt, subjectDn)`
   - react-query 10 秒自动刷新
5. 批准 → `POST /api/v1/collector/{channelId}/trust-cert { thumbprint }`：
   - `OpcUaCertificateStore.approve(thumbprint)` 把 `.der` 从 `pending/` 移到 `trusted/`，同步删除 `.json`
   - 审计日志 `CERT_TRUST`
   - 发布 `ChannelCertificateApprovedEvent` → 同步自动解除报警（`AUTO` 原因）
6. 拒绝 → `DELETE /api/v1/collector/cert-pending/{thumbprint}`：
   - `OpcUaCertificateStore.reject(thumbprint)` 把 `.der` 移到 `rejected/` 留证（不再触发报警，但允许后续审计）
7. Channel 重连周期到达后自动重试连接

> **事件链同步性**：`ApplicationEventPublisher` 默认走 Spring 同步多播器；`@EnableAsync` 仅作用于 `@Async` 注解（如 webhook executor）。因此 pending 事件保存报警与 `addPending` 在同一线程完成，前端见到 cert 时报警必已存在；不存在「approve 早于 pending listener」的竞态。

### 8.3.bis 客户端 .pfx 上传 → PEM 服务端转换（已实现）

ADMIN 把客户端身份用 `.pfx` (PKCS#12) 上传到 `POST /api/v1/secrets/opcua/cert`，后端用 `KeyStore.getInstance("PKCS12")` 解析后再以 encrypted PKCS#8 PEM 形式写到 `secret://opcua/<name>.pem`，密码写到 `secret://opcua/<name>.pem.password`。OPC UA Transport 仍由 `OpcUaCertificateLoader` 读 PEM——零代码改动。

- 关键 invariant：磁盘只保留 PEM，不保留原 .pfx（已解析过；保留二进制只增加攻击面）。
- 也允许运维直接走 `POST /api/v1/secrets` 写 PEM 文本——两条路径产出的 secret 可被 channel 配置同等消费。
- 多 alias keystore 必须显式提供 `alias` 表单字段；否则 400。
- 文件大小硬上限 64KB；name 必须匹配 `^[a-zA-Z0-9._-]+$` 且 ≤ 100 字符（防路径遍历）。
- 审计事件 action `SECRET_PFX_UPLOAD`、targetId `secret://opcua/<name>.pem`，包含 cert SHA-256 指纹。
- PEM 私钥再加密用 PBE-SHA1-3DES（OID 1.2.840.113549.1.12.1.3，stock JDK 唯一能 OID-roundtrip 的常见 PBE 算法；强度等同上传的原 .pfx）。

### 8.4 REST 端点（仅 ADMIN）

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/v1/secrets` | POST | `{ ref, value }` 写入 |
| `/api/v1/secrets/{ref}` | DELETE | 删除 |
| `/api/v1/secrets` | GET | 仅返回 ref 列表 |
| `/api/v1/secrets/opcua/cert` | POST | multipart 上传 .pfx — 后端解析 PKCS12 并转存为 PEM (encrypted PKCS#8)（已实现） |
| `/api/v1/collector/cert-pending` | GET | 列出待审批 OPC UA 服务器证书（已实现） |
| `/api/v1/collector/{channelId}/trust-cert` | POST | 批准 OPC UA 服务器证书（已实现，body `{ thumbprint }`） |
| `/api/v1/collector/cert-pending/{thumbprint}` | DELETE | 拒绝 OPC UA 服务器证书（已实现） |

### 8.5 与现有 .env 集成

```yaml
# application.yml
ems:
  secrets:
    dir: ${EMS_SECRETS_DIR:${user.home}/.ems/secrets}
```

```yaml
# compose.yml
services:
  ems-app:
    volumes:
      - ${HOME}/.ems/secrets:/home/ems/.ems/secrets:ro
```

`.env.example` 新增 `EMS_SECRETS_DIR=` 占位。

### 8.6 安全审查清单

- [x] 路径遍历防御：normalize 后须落在 `secretsDir` 之下
- [x] 文件权限：写入立即 `setPosixFilePermissions(rw-------)`
- [x] 启动检查：`secretsDir` 权限 ≤ 700，否则启动失败
- [x] 日志：永不记录密钥值
- [x] 审计：所有 secret 写/删/批准操作进 `audit_log`
- [x] HTTPS 强制
- [x] OPC UA 默认不信任未审批证书
- [x] DTO：永不返回明文，仅返回 `secret://xxx` 引用

### 8.7 工作量

约 1.5 天。

---

## 9. 诊断 / 可观测性（Section 7/8）

### 9.1 ChannelRuntimeState

```java
public record ChannelRuntimeState(
    Long channelId,
    String protocol,
    ConnectionState connState,                       // CONNECTING / CONNECTED / DISCONNECTED / ERROR
    Instant lastConnectAt,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    String lastErrorMessage,                         // 截断 200 字符
    long successCount24h,
    long failureCount24h,
    long avgLatencyMs,                               // 滑动平均（最近 100 次）
    Map<String, Object> protocolMeta                 // OPC UA: subscriptionId; MQTT: brokerVersion
) {}
```

由 `ChannelStateRegistry`（`ConcurrentHashMap<Long, ChannelRuntimeState>`）维护。

### 9.2 24h 计数器

```java
class HourlyCounter {
    private final long[] success = new long[24];
    private final long[] failure = new long[24];
    private volatile int currentSlot;

    void recordSuccess() { rollIfNeeded(); success[currentSlot]++; }
    void recordFailure() { rollIfNeeded(); failure[currentSlot]++; }
    long total24h(boolean ok) { return Arrays.stream(ok ? success : failure).sum(); }
}
```

每分钟 flush 一次到 `collector_metrics`，避免高频写库。

### 9.3 REST 诊断端点

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/v1/collector/state` | GET | 全局状态摘要 |
| `/api/v1/collector/{channelId}/state` | GET | 单 channel 详细 |
| `/api/v1/collector/{channelId}/recent-samples?limit=20` | GET | 最近 N 条样本（环形 buffer） |
| `/api/v1/collector/{channelId}/test` | POST | 手动连接测试 |
| `/api/v1/collector/{channelId}/reconnect` | POST | 强制重连 |
| `/api/v1/collector/{channelId}/metrics?from=&to=` | GET | 历史指标 |

### 9.4 Spring Actuator + Micrometer

```java
@Component
class CollectorHealthIndicator implements HealthIndicator {
    public Health health() {
        var all = registry.getAll();
        var disconnected = all.stream().filter(s -> s.connState() == DISCONNECTED).count();
        return disconnected == 0
            ? Health.up().withDetail("channels", all.size()).build()
            : Health.status("DEGRADED").withDetail("disconnected", disconnected).build();
    }
}
```

Prometheus 指标（`/actuator/prometheus`）：

```
ems_collector_channels_total{protocol}
ems_collector_channels_state{state}
ems_collector_samples_total{protocol,result}
ems_collector_sample_latency_seconds{protocol}    # histogram
ems_collector_reconnect_total{channel_id}
```

### 9.5 前端诊断页 `/collector`

升级为表格 + Drawer 详情。

| 列 | 内容 |
|---|---|
| 协议 | 标签（带颜色） |
| 名称 | channel name |
| 状态 | 圆点 + 文字（已连接/重连中/已断开/错误） |
| 最近成功 | 相对时间，hover 绝对时间 |
| 24h 成功率 | `98.5%`，<95% 红色高亮 |
| 平均延迟 | `45 ms` |
| 最后错误 | 截断 + Tooltip |
| 操作 | [测试] [重连] [详情] |

Drawer 详情包含：完整状态、订阅列表、最近 20 条样本（OPC UA 表格 / MQTT JSON 折叠）、24h Sparkline。

WebSocket `/ws/realtime` 扩展 topic `collector.state` → 推送增量更新，前端无需轮询。

### 9.6 报警联动

- 连续 5 次失败 → 触发 `COMMUNICATION_FAULT`
- 证书未批准 → 触发 `OPC_UA_CERT_PENDING`
- VIRTUAL 协议**不参与报警**

### 9.7 工作量

约 2 天。

---

## 10. 测试策略（Section 8/8 — Part 1）

### 10.1 测试金字塔

| 层级 | 工具 | 覆盖 |
|---|---|---|
| 单元 | JUnit 5 + Mockito | SecretResolver / HourlyCounter / VirtualSignalGenerator / JSONPath 提取 |
| 集成 | Testcontainers | mosquitto:2.0 / Eclipse Milo demo server / PostgreSQL |
| E2E | Playwright | 创建 → 测试 → 诊断 → 删除 |

目标覆盖：单元 + 集成 ≥ 80%；关键路径（SecretResolver, OpcUaTransport, MqttTransport）≥ 90%。

### 10.2 关键集成测试

**MqttTransportIT**（mosquitto:2.0 testcontainer）：
- 订阅 retained message → JSONPath `$.value` → 落库
- 重连：broker 断开 5s 后恢复 → 自动重订阅
- TLS 单向认证（mosquitto 自签证书）
- 凭据从 `secret://` 解析

**OpcUaTransportIT**（Milo demo server，端口 12686）：
- Sign+Encrypt 模式 + 证书首次拒绝、批准后通过
- Subscribe 4 节点 + 收到 PublishResponse
- 服务端关闭 → 客户端检测 → 重连
- Read with quality bad → 标记失败但不抛异常

**VirtualTransportTest**（纯单元测试）：
- CONSTANT 始终返回 value
- SINE: t=0/T/4/T/2/3T/4 时值匹配公式
- RANDOM_WALK: 1000 次值在 `[min, max]` 内 + 步长 ≤ maxStep
- CALENDAR_CURVE: 工作日 vs 周末 09:00 不同

**SecretResolverIT**（@TempDir）：
- 写入 → 文件权限 600
- 路径遍历 `secret://../../../etc/passwd` → SecurityException
- 不存在 ref → exists() false

### 10.3 Playwright E2E

3 个关键路径：
1. Modbus TCP CRUD + 测试连接
2. OPC UA 创建 + 证书审批
3. VIRTUAL 创建 + 验证产出数据写入 measurement

```typescript
test('admin creates OPC UA channel and verifies diagnostics', async ({ page }) => {
  await login(page, 'admin');
  await page.goto('/meters');
  await page.click('button:has-text("新增通道")');
  await page.selectOption('[name="protocol"]', 'OPC_UA');
  await page.fill('[name="endpointUrl"]', 'opc.tcp://test:62541');
  await page.click('button:has-text("测试连接")');
  await expect(page.locator('.test-result')).toContainText('成功');
  await page.click('button:has-text("保存")');
  await page.goto('/collector');
  await expect(page.locator(`tr:has-text("test-channel")`)).toContainText('已连接');
});
```

---

## 11. 风险矩阵（Section 8/8 — Part 2）

| 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|
| Eclipse Milo 与 Spring Boot 3.3 / Java 21 兼容性 | 高 | 中 | 先做 spike：独立工程验证 Milo 0.6.x 启动；隔离到独立 module 减少依赖污染 |
| OPC UA 证书管理复杂度 | 中 | 高 | 首版只支持 PKCS12 + 单证书；多证书轮换归入 future work |
| MQTT broker 性能瓶颈 | 中 | 低 | 单 broker 连接 + topic 复用；订阅数 > 1000 才需要分片 |
| JSONB 反序列化 schema 漂移 | 高 | 中 | `@JsonTypeInfo` + sealed interface 严格约束；Flyway 不修改既有 JSON 结构 |
| 凭据文件权限被运维误改 | 高 | 低 | 启动时校验 `secretsDir` 权限 ≤ 700，不符则启动失败 |
| 虚拟协议数据被误认为真实数据 | 中 | 中 | DB 字段 `is_virtual`；前端图表标识；不参与报警与对账 |

---

## 12. 迁移计划（Section 8/8 — Part 3）

| Phase | 内容 | 工时 | 回滚策略 |
|---|---|---|---|
| 1 | 数据模型（Flyway migration） | 1 天 | 保留旧表 |
| 2 | Modbus 迁移到 ChannelRegistry | 2 天 | feature flag `ems.collector.use-channel=false` |
| 3 | VIRTUAL 协议 | 2 天 | 低风险，可生产试点 1-2 channel |
| 4 | OPC UA（含 Milo spike） | 5 天 | 先连 1 个非关键 PLC |
| 5 | MQTT（含 mosquitto 部署） | 3 天 | - |
| 6 | 诊断页 + 报警联动 | 2 天 | - |
| 7 | Actuator + Prometheus | 1 天 | - |

**总工期：约 16 个工作日**（不含 buffer）。

---

## 13. 文档交付物

- `docs/superpowers/specs/2026-04-30-collector-protocols-design.md`（本文档）
- `docs/superpowers/plans/2026-04-30-collector-protocols-plan.md`（实施计划，下一步生成）
- `docs/product/collector-protocols-user-guide.md`（运维使用手册）
- `docs/api/collector-api.md`（REST API 参考）
- `docs/ops/opcua-cert-management.md`（OPC UA 证书运维 SOP）

---

## 14. 总体工作量汇总

| 阶段 | 工时 |
|---|---|
| 数据模型 + Channel 抽象 | 1 |
| Modbus 适配 | 2 |
| VIRTUAL | 2 |
| OPC UA | 5 |
| MQTT | 3 |
| 凭据 + 安全 | 1.5 |
| 诊断 + 可观测性 | 2 |
| 前端表单 | 5 |
| 测试与联调 | 3 |
| 文档与 SOP | 1 |
| **合计** | **约 25.5 人日** |
