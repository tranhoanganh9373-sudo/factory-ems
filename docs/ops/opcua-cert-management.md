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

`NONE` 模式下整条链路是**明文 + 无身份认证**，只能用于物理隔离的 OT 网络。生产环境优先选 `SIGN_AND_ENCRYPT`。

---

## 2. 当前实现状态（v1.1）—— 必读

| 能力 | v1.1 现状 |
|---|---|
| `SecurityMode.NONE` 端到端连接 | ✅ 已支持，`/collector` 页可创建并稳定运行 |
| `SecurityMode.SIGN` 端到端连接 | ✅ 已支持，PEM 客户端私钥经 `OpcUaCertificateLoader` 加载；服务端证书走信任流 |
| `SecurityMode.SIGN_AND_ENCRYPT` 端到端连接 | ✅ 已支持（与 SIGN 同路径） |
| `OpcUaCertificateStore` Bean | ✅ 已实现（`com.ems.collector.cert`），暴露 `addPending` / `listPending` / `approve` / `reject` / `isTrusted` |
| `OpcUaCertificateStore` 接入 transport 启动流程 | ✅ 已接入；`OpcUaTransport.buildCertificateValidator` 校验服务端证书 |
| 服务端证书审批 REST API | ✅ 已实装：`GET /api/v1/collector/cert-pending` / `POST /api/v1/collector/{channelId}/trust-cert` / `DELETE /api/v1/collector/cert-pending/{thumbprint}` |
| 前端证书审批页 | ✅ `/admin/cert-approval`（ADMIN-only） |
| `OPC_UA_CERT_PENDING` 报警自动联动 | ✅ pending 时创建 ACTIVE 报警，approve 时自动 RESOLVE |
| `.pfx` 客户端证书 multipart 上传 | ✅ `POST /api/v1/secrets/opcua/cert`（ADMIN）— 后端解析 PKCS#12 → 落盘成 PEM (encrypted PKCS#8) — 见 §5.2 |

> **结论**：v1.1 三种 `SecurityMode` 全可用。日常运维走 §3 信任目录约定 + §4 审批 SOP；客户端 `.pfx` 走 §5.2 的 multipart 上传端点。

---

## 3. 证书存储目录约定

`OpcUaCertificateStore`（`com.ems.collector.cert.OpcUaCertificateStore`）按下列规则管理目录：

```
${ems.secrets.dir}                       # 默认 ${user.home}/.ems/secrets
└── opcua/
    └── certs/
        ├── trusted/                     # 受信任的服务器证书白名单 (DER)
        │   └── <displayName>-<sha256_thumbprint>.der
        ├── pending/                     # 待审批：transport 自动落入 + 元数据 JSON
        │   ├── <thumbprint>.der
        │   └── <thumbprint>.json        # { thumbprint, channelId, endpointUrl, firstSeenAt, subjectDn }
        └── rejected/                    # 已拒绝（保留以备审计）
            └── <thumbprint>.der
```

- **`${ems.secrets.dir}`**：通过 application.yml 的 `ems.secrets.dir` 注入，默认 `~/.ems/secrets`
- **文件格式**：DER 编码 X.509（**非 PEM**，无 base64 包裹）
- **`trusted/` 文件名**：`<displayName>-<thumbprint>.der`，其中 `<thumbprint>` 是证书 DER 字节的 **SHA-256 十六进制**（小写 64 字符）
- **`pending/` / `rejected/` 文件名**：`<thumbprint>.der`（不带 displayName 前缀）
- **权限**：代码用 POSIX 把 `pending/` `.der` 与 `.json` 设为 `rw-------`（600）；`trusted/` 目录手工设 `700`（运维责任，下文 §6 给出命令）

> `OpcUaCertificateStore` 暴露的能力（v1.1 transport 实际调用）：
> - `isTrusted(X509Certificate)` —— 按 thumbprint 查 `trusted/`
> - `addPending(X509Certificate, channelId, endpointUrl)` —— 把不在 trusted 的服务端证书写入 `pending/`，幂等
> - `listPending()` —— 扫描 `pending/`，返回 `PendingCertificate` 元数据列表
> - `approve(thumbprint)` —— 把 `.der` 从 `pending/` 移到 `trusted/`，删除 `.json`
> - `reject(thumbprint)` —— 把 `.der` 从 `pending/` 移到 `rejected/`，删除 `.json`

---

## 4. v1.1 当前可执行的运维操作

### 4.1 验证 collector 进程已创建证书目录

```bash
ls -la ~/.ems/secrets/opcua/certs/
# 预期：trusted/ pending/ rejected/ 三个子目录
ls -la ~/.ems/secrets/opcua/certs/trusted/
ls -la ~/.ems/secrets/opcua/certs/pending/
```

容器化部署时 `~/.ems` 应映射到持久卷，否则证书会随容器丢失。

### 4.2 `SecurityMode.NONE` 通道使用

直接在 `/collector` 页创建 OPC UA channel 时把 `安全模式` 选为 `NONE`，不要填 `证书引用` / `证书密码引用`。`READ` 模式测点会立即开始轮询。

### 4.3 `SecurityMode.SIGN` / `SIGN_AND_ENCRYPT` 通道使用

1. 把客户端 PEM 证书与私钥放入 secret 路径（详见 §6）
2. 在 `/collector` 创建 channel 时填 `证书引用` 与 `证书密码引用`（如需要）
3. 启用通道；首次连接服务端时若服务端证书未在 `trusted/`，transport 抛 `TransportException` + 同步触发 `OPC_UA_CERT_PENDING` 报警
4. 按 §5 完成审批后通道自动重连

---

## 5. 证书审批 SOP（v1.1 已实装）

### 5.1 首次连接 + 证书审批

1. 创建 OPC UA channel，`SecurityMode = SIGN_AND_ENCRYPT`，启用
2. transport `start()` 调用 Milo `connect`，服务端返回其 X.509 证书
3. `OpcUaTransport.buildCertificateValidator` 调用 `OpcUaCertificateStore.isTrusted(serverCert)`
4. 不在 trusted 目录 → 调 `addPending(cert, channelId, endpointUrl)` 把 `.der` + `.json` 写到 `pending/`，发布 `ChannelCertificatePendingEvent`，抛 `TransportException` 拒绝连接
5. `CertificatePendingListener` 同步创建 `OPC_UA_CERT_PENDING` 报警（同 channel 同时只有一条 ACTIVE）
6. 管理员打开 `/admin/cert-approval`（ADMIN-only） → 列出待审批证书，每 10 秒自动刷新
7. 点 **「批准」** → 前端调 `POST /api/v1/collector/{channelId}/trust-cert { thumbprint }`
8. 后端 `approve(thumbprint)` 把 `.der` 从 `pending/` 移到 `trusted/`，写审计 `CERT_TRUST`，发布 `ChannelCertificateApprovedEvent`，自动 RESOLVE 报警
9. 下次 transport 重连周期到达即恢复 `CONNECTED`

也可通过命令行直接审批：

```bash
# 列出待审批
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/cert-pending

# 批准
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"thumbprint":"<hex64>"}' \
  http://localhost:8888/api/v1/collector/<channelId>/trust-cert

# 拒绝
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/cert-pending/<hex64>
```

### 5.2 客户端 `.pfx` / PEM 私钥上传

#### 5.2.1 推荐：multipart 上传 `.pfx`（ADMIN）

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@client.pfx" \
  -F "name=plc-line1" \
  -F "password=<pfx-password>" \
  http://localhost:8888/api/v1/secrets/opcua/cert
```

后端 `KeyStore.getInstance("PKCS12")` 解析 .pfx，把证书 + 加密 PKCS#8 私钥拼成 PEM，落盘成两个 secret：

- `secret://opcua/<name>.pem` — 证书 + encrypted PKCS#8 私钥
- `secret://opcua/<name>.pem.password` — PEM 私钥密码（同表单 `password`）

ChannelEditor 把 `certRef` 填 `secret://opcua/plc-line1.pem`、`certPasswordRef` 填 `secret://opcua/plc-line1.pem.password` 即可。

约束：

- 文件大小硬上限 64KB；超过返回 400
- `name` 必须匹配 `^[a-zA-Z0-9._-]+$` 且 ≤ 100 字符（防路径遍历）
- 多 alias 的 keystore 需额外传 `-F alias=<alias-name>`；否则返回 400
- 错误密码返回 400 `{ "error": "invalid keystore password" }`
- 写审计事件 `SECRET_PFX_UPLOAD`，包含 cert SHA-256 指纹

#### 5.2.2 备用：直接写 PEM 文本

如果手头已经是 PEM 而非 .pfx，可走通用 `POST /api/v1/secrets` 写文本 secret：

1. 把 PEM 内容（`CERTIFICATE` + `ENCRYPTED PRIVATE KEY` 拼接）通过 `POST /api/v1/secrets` 写到 `secret://opcua/<name>.pem`
2. 把私钥密码通过同一接口写到 `secret://opcua/<name>.pem.password`
3. 在 ChannelEditor 把 `certRef` / `certPasswordRef` 指向上述 ref

这条路径与 5.2.1 产出完全等价，OPC UA Transport 同样消费 PEM。

### 5.3 证书撤销 / 不再信任

直接调 `DELETE /api/v1/collector/cert-pending/<thumbprint>` 把 pending 移到 rejected。如果证书已经在 trusted 目录里要撤销，目前用 §6 手工方案删文件。

---

## 6. 手工补救（绕开审批端点 / 直接管理目录）

通常 §5 的 REST 流就够了。下列手工流程仅在审批 UI 不可用、或需要批量预置证书时使用。

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

容器部署：把 `~/.ems` 挂卷的 owner 改成运行 collector 的 uid（如 `app:app`），禁止 world 读。

---

## 7. 故障排查速查

| 现象 | 排查 |
|---|---|
| channel 一直 `ERROR`，错误是 `Untrusted server certificate` | 服务端证书已落入 `pending/` 并触发 `OPC_UA_CERT_PENDING` 报警，按 §5.1 在 `/admin/cert-approval` 审批 |
| 把证书放入 trusted 目录后仍然 ERROR | 检查文件名是否含 thumbprint 后缀（`*-<sha256>.der`）；权限是否 600；指纹大小写是否小写 hex |
| `OpcUaCertificateStore.init()` 失败 | 检查 `${ems.secrets.dir}` 目录可写；常见原因是容器 uid 不匹配 |
| 启动后 trusted 目录是空的但 transport CONNECTED | 当前是 `NONE` 模式，不需要证书。若想强制证书校验请把通道改成 `SIGN` 或 `SIGN_AND_ENCRYPT` |

---

## 8. 相关链接

- 用户操作指南：[docs/product/collector-protocols-user-guide.md](../product/collector-protocols-user-guide.md)
- API 参考：[docs/api/collector-api.md](../api/collector-api.md)
- 设计文档：`docs/superpowers/specs/2026-04-30-collector-protocols-design.md`（§6.2、§8.3）
- 旧版 collector runbook（Modbus-only）：[collector-runbook.md](./collector-runbook.md)
