# OPC UA 证书管理 · 运维 SOP

> **适用版本**：v1.1（CP-Phase 1-9 完成时）
> **受众**：运维 / 平台工程
> **配套**：协议使用指南见 [docs/product/collector-protocols-user-guide.md](../product/collector-protocols-user-guide.md)；REST API 见 [docs/api/collector-api.md](../api/collector-api.md)

---

## 1. 背景：OPC UA 信任模型

OPC UA 客户端 / 服务端通过 X.509 证书互相验证。`SecurityMode` 决定双向认证的强度：

| Mode | 报文加密 | 报文签名 | 客户端必须信任服务端证书 | 客户端必须出示证书给服务端 |
|---|---|---|---|---|
| `NONE` | 否 | 否 | 否 | 否 |
| `SIGN` | 否 | 是 | 是 | 是 |
| `SIGN_AND_ENCRYPT` | 是 | 是 | 是 | 是 |

`NONE` 模式下整条链路是**明文 + 无身份认证**；只能用于物理隔离的 OT 网络。生产环境应优先选 `SIGN_AND_ENCRYPT`。

---

## 2. 当前实现状态（v1.1）—— 必读

为避免误用，把当前 collector 模块对 OPC UA 证书的支持现状显式标注：

| 能力 | v1.1 现状 |
|---|---|
| `SecurityMode.NONE` 端到端连接 | ✅ 已支持，`/collector` 页可创建并稳定运行 |
| `SecurityMode.SIGN` 端到端连接 | ⚠️ 仅 schema 与 endpoint 选择就位，**transport 启动期未加载客户端证书** |
| `SecurityMode.SIGN_AND_ENCRYPT` 端到端连接 | ⚠️ 同上，未端到端打通 |
| `OpcUaCertificateStore` Bean | ✅ 已实现（`com.ems.collector.cert`），暴露 `isTrusted` / `approve` / `thumbprint` 方法 |
| `OpcUaCertificateStore` 接入 transport 启动流程 | ❌ 未接入；transport 不会调用 `isTrusted()` 校验服务器证书 |
| 服务端证书审批 REST API | ❌ spec §6.2 描述的 `POST /api/v1/collector/{id}/trust-cert` **未实装** |
| `.pfx` 客户端证书 multipart 上传 | ❌ spec §8.3 描述的 `POST /api/v1/secrets/opcua/cert` **未实装** |

> **结论**：v1.1 唯一可用的 OPC UA 模式是 `SecurityMode.NONE`，请通过物理 / VLAN 隔离 + nginx 反代鉴权来保证安全。本文档剩余章节描述 `OpcUaCertificateStore` 的现存目录约定，以及未来 v2 启用 SIGN/SIGN_AND_ENCRYPT 时的预期 SOP；当前不要按 v2 SOP 操作。

---

## 3. 证书存储目录约定

`OpcUaCertificateStore`（`com.ems.collector.cert.OpcUaCertificateStore`）按下列规则管理目录：

```
${ems.secrets.dir}                       # 默认 ${user.home}/.ems/secrets
└── opcua/
    └── certs/
        └── trusted/                     # 受信任的服务器证书白名单 (DER)
            └── <displayName>-<sha256_thumbprint>.der
```

- **`${ems.secrets.dir}`**：通过 application.yml 的 `ems.secrets.dir` 注入，默认 `~/.ems/secrets`
- **文件格式**：DER 编码 X.509（**非 PEM**，无 base64 包裹）
- **文件名**：`<displayName>-<thumbprint>.der`，其中 `<thumbprint>` 是证书 DER 字节的 **SHA-256 十六进制**（小写 64 字符）
- **权限**：建议目录 `700`、文件 `600`（运维责任，代码不强制；下文 §6 给出命令）

> 当前 `OpcUaCertificateStore` 暴露的能力：
> - `isTrusted(X509Certificate)` —— 按 thumbprint 后缀查找
> - `approve(X509Certificate, displayName)` —— 把证书写入 trusted 目录
> - `thumbprint(X509Certificate)` —— 计算 SHA-256
>
> 这些方法目前**仅在单元测试 / 后续 v2 transport 集成时使用**。v1.1 OPC UA transport 不会调用它们。

---

## 4. v1.1 当前可执行的运维操作

### 4.1 验证 collector 进程已创建 trusted 目录

```bash
ls -la ~/.ems/secrets/opcua/certs/trusted/
# 预期：drwx------ (700) 空目录或仅含 .der 文件
```

容器化部署时 `~/.ems` 应映射到持久卷，否则证书会随容器丢失。

### 4.2 `SecurityMode.NONE` 通道使用

直接在 `/collector` 页创建 OPC UA channel 时把 `安全模式` 选为 `NONE`，不要填 `证书引用` / `证书密码引用`。`READ` 模式测点会立即开始轮询。

### 4.3 验证非 NONE 模式不可用（防误操作）

如果有人误把通道配置为 `SIGN_AND_ENCRYPT`：
1. 后端校验通过（schema 不阻挡）
2. transport 启动期 Eclipse Milo 抛 `unable to find security policy` 或 TLS 握手失败
3. `/collector` 页该行 `连接状态 = ERROR`，`最后错误` 含具体异常

应立即把 `安全模式` 改回 `NONE` 或停用该 channel，等待 v2 升级。

---

## 5. v2 预期 SOP（未实装，仅供参考）

以下流程在 transport 接入 `OpcUaCertificateStore` 且证书审批 REST 端点上线后才适用，**v1.1 不要执行**。

### 5.1 首次连接 + 证书审批

1. 创建 OPC UA channel，`SecurityMode = SIGN_AND_ENCRYPT`，启用
2. transport `start()` 调用 Milo `connect`，服务端返回其 X.509 证书
3. transport 调用 `OpcUaCertificateStore.isTrusted(serverCert)`
4. 不在 trusted 目录 → 拒绝连接，`ChannelStateRegistry.lastErrorMessage` 记 `"Untrusted server certificate: SHA-256: <hex>"`
5. 触发告警 `OPC_UA_CERT_PENDING`
6. 管理员在 `/collector` 详情 drawer 看到指纹 → 点 **「批准证书」**
7. 前端调 `POST /api/v1/collector/{id}/trust-cert { thumbprint }`
8. 后端调 `OpcUaCertificateStore.approve()` 把服务端证书写入 trusted 目录 + 写审计 `CERT_TRUST`
9. 自动触发 `reconnect` → channel 进入 `CONNECTED`

### 5.2 客户端 `.pfx` 上传

1. 在 secret 管理 UI 选 OPC UA 通道 → 上传 `.pfx`
2. 前端 multipart `POST /api/v1/secrets/opcua/cert`
3. 后端落到 `~/.ems/secrets/opcua/certs/<channelId>.pfx`，权限 600
4. ChannelEditor 把 `certRef` 字段填 `secret://opcua/<channelId>.pfx`

### 5.3 证书撤销 / 不信任

通过未来的 `DELETE /api/v1/collector/{id}/trust-cert?thumbprint=...` 实现；v1.1 用 §6 手工方案。

---

## 6. 手工补救（v1.1 临时方案）

如果生产侧确实需要先用 SIGN/SIGN_AND_ENCRYPT，且后端 transport 已在私有补丁中接入 `OpcUaCertificateStore`，运维可手工管理目录。

### 6.1 把服务端证书加入信任列表

```bash
# 1. 从服务端取出证书（运维侧已有 .der 或从 .pem 转换）
openssl x509 -in server.pem -outform DER -out server.der

# 2. 计算 SHA-256 指纹（小写 hex）
THUMB=$(openssl dgst -sha256 -binary server.der | xxd -p -c 256)

# 3. 命名并放入 trusted 目录
sudo -u ems-svc cp server.der \
  ~/.ems/secrets/opcua/certs/trusted/plc-line1-${THUMB}.der

# 4. 修文件权限
sudo chmod 600 ~/.ems/secrets/opcua/certs/trusted/plc-line1-${THUMB}.der
```

### 6.2 撤销 / 不再信任

```bash
ls ~/.ems/secrets/opcua/certs/trusted/
# 找到对应 displayName-thumbprint.der
rm ~/.ems/secrets/opcua/certs/trusted/plc-line1-<thumbprint>.der

# 重启相关 channel transport（点 /collector 的「重连」按钮，或重启 collector 服务）
```

### 6.3 检查目录权限

```bash
chmod 700 ~/.ems/secrets/opcua/
chmod 700 ~/.ems/secrets/opcua/certs/
chmod 700 ~/.ems/secrets/opcua/certs/trusted/
chmod 600 ~/.ems/secrets/opcua/certs/trusted/*.der
```

容器部署：把 `~/.ems` 挂卷的 owner 设置为运行 collector 的 uid（如 `app:app`），并禁止 world 读。

---

## 7. 故障排查速查

| 现象 | 排查 |
|---|---|
| channel 一直 `ERROR`，错误是 `Untrusted server certificate` | v1.1 transport 不会主动报这个；如果出现说明已切到 v2，按 §5.1 审批 |
| 把证书放入 trusted 目录后仍然 ERROR | 检查文件名是否含 thumbprint 后缀（`*-<sha256>.der`）；权限是否 600；指纹大小写是否小写 hex |
| `OpcUaCertificateStore.init()` 失败 | 检查 `${ems.secrets.dir}` 目录可写；常见原因是容器 uid 不匹配 |
| 启动后 trusted 目录是空的但 transport CONNECTED | 当前是 `NONE` 模式，不需要证书；若要强制证书校验请等 v2 |

---

## 8. 相关链接

- 用户操作指南：[docs/product/collector-protocols-user-guide.md](../product/collector-protocols-user-guide.md)
- API 参考：[docs/api/collector-api.md](../api/collector-api.md)
- 设计文档：`docs/superpowers/specs/2026-04-30-collector-protocols-design.md`（§6.2、§8.3）
- 旧版 collector runbook（Modbus-only）：[collector-runbook.md](./collector-runbook.md)
