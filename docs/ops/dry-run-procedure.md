# 装机 Dry-Run · 本地无硬件演练

**目的：** 没有真实 Modbus 仪表时跑通 §11.3 装机 SOP，训练实施工程师 / 复现现场故障。

**前提：**
- 本机能跑 `mvn` + `docker compose`
- 端口 5502 / 8888 / 5432 / 8086 空闲

---

## 1. 起 Modbus 模拟器

```bash
# 第一次需要先把依赖装到本地仓库
mvn -pl tools/modbus-simulator -am install -DskipTests -q

# 然后从模块内启
(cd tools/modbus-simulator && mvn exec:java -q)
# 默认 listen 0.0.0.0:5502 unit=1
# 输出：[sim] Modbus TCP slave listening on 0.0.0.0:5502 unit=1
```

预置寄存器（与 `deploy/collector.yml.example` 对应）：

| address | type | 含义 | 行为 |
|---|---|---|---|
| 0x2000 | FLOAT32 | voltage_a | 218..222 V 正弦漂移 |
| 0x2014 | FLOAT32 | power_active | 4500..5500 W 抖动 |
| 0x4000 | UINT32 | energy_total | ×0.01 kWh，单调递增 |

可换端口 / unit-id：`mvn exec:java -Dexec.args="--port 502 --unit 3"`。

## 2. 写演练 collector.yml

```bash
cat > deploy/collector.yml <<'YML'
ems:
  collector:
    enabled: true
    buffer:
      path: ./data/collector/buffer.db
      max-rows-per-device: 100000
      ttl-days: 7
      flush-interval-ms: 30000
    devices:
      - id: sim-meter
        meter-code: MOCK-M-ELEC-001     # 必须库里存在
        protocol: TCP
        host: 127.0.0.1
        port: 5502
        unit-id: 1
        polling-interval-ms: 2000
        timeout-ms: 1000
        retries: 3
        backoff-ms: 10000
        registers:
          - name: voltage_a
            address: 0x2000
            count: 2
            function: HOLDING
            data-type: FLOAT32
            byte-order: ABCD
            ts-field: voltage_a
          - name: power_active
            address: 0x2014
            count: 2
            function: HOLDING
            data-type: FLOAT32
            scale: 0.001
            ts-field: power_active
          - name: energy_total
            address: 0x4000
            count: 2
            function: HOLDING
            data-type: UINT32
            scale: 0.01
            ts-field: energy_total
            kind: ACCUMULATOR
YML
```

## 3. 跑 preflight

```bash
EMS_DB_HOST=localhost EMS_INFLUX_HOST=localhost \
  scripts/collector-preflight.sh --config deploy/collector.yml --env .env
```

期望：device 连通性 5) 这一段对 `sim-meter`/127.0.0.1:5502 显示 `[ OK ]`。

## 4. 起栈（前端 + 后端 + DB）

```bash
EMS_COLLECTOR_ENABLED=true docker compose up -d
docker compose logs -f factory-ems | head -50
# 看到 "collector started: 1 device(s)"
```

## 5. 验证数据流（按 §11.3 「装机当天」清单）

```bash
# (1) collector status
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8888/api/v1/collector/status \
  | python3 -m json.tool

# (2) 健康端点
curl -s http://localhost:8888/actuator/health/collector

# (3) Influx 看数据
docker compose exec influxdb influx query "from(bucket:\"factory_ems\") \
  |> range(start: -5m) \
  |> filter(fn: (r) => r._measurement == \"energy_reading\") \
  |> last()"

# (4) Metrics
curl -s http://localhost:8888/actuator/metrics/ems.collector.read.success?tag=device:sim-meter
```

期望：state=HEALTHY，每 2 秒 1 笔新数据，3 个 ts-field 都填充，energy_total_delta 第二周期起 > 0。

## 6. 故障复现：杀模拟器看降级 + buffer

```bash
# 在第 1 步的终端 Ctrl-C 杀模拟器
# 等 ~30 秒后：
curl -s http://localhost:8888/api/v1/collector/status | grep state
# 期望: state=DEGRADED 然后 UNREACHABLE

# 看 audit 留痕
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8888/admin/audit?action=COLLECTOR_STATE_CHANGE" | head

# 看 buffer 是否在累计（写失败时）
sqlite3 ./data/collector/buffer.db "SELECT count(*), max(created_at) FROM collector_buffer WHERE sent=0"
```

## 7. 恢复：重启模拟器看自动恢复 + flush

```bash
(cd tools/modbus-simulator && mvn exec:java -q) &

# 等 ~30 秒：
curl -s http://localhost:8888/api/v1/collector/status | grep state
# 期望: state=HEALTHY

# buffer 应该被 BufferFlushScheduler 清空
sqlite3 ./data/collector/buffer.db "SELECT count(*) FROM collector_buffer WHERE sent=0"
```

## 8. 配置热加载

```bash
# 改 collector.yml 加一个 device 或改 polling-interval-ms
$EDITOR deploy/collector.yml

# 触发 reload
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8888/api/v1/collector/reload
# 期望返回：{"added":[…], "modified":[…], "removed":[…], "unchanged": N}
```

## 9. 收尾

```bash
docker compose down -v
pkill -f modbus-simulator || true
rm -rf ./data/collector
```

---

## 现场反哺要点

每跑一次 dry-run，把下列信息回写到 `docs/ops/collector-runbook.md`：
- 步骤 5/6 客户最常踩的坑（认证 token、防火墙、寄存器地址 0/1-based）
- 步骤 6 典型降级延迟（用于现场告警阈值调整）
- preflight 漏检的项（补到脚本）
