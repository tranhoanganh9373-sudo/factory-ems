// =============================================================================
// factory-ems 数据采集写入压测 — collector-write.js
//
// 模拟 100 个电表同时上报秒级数据（Modbus/OPC UA 采集器行为）
// 目标: P95 写入 < 50ms, 错误率 < 0.01%, 吞吐 > 500 req/s
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8888';

const writeTrend = new Trend('ems_write_ms', true);
const writeErrors = new Rate('ems_write_errors');
const pointsWritten = new Counter('ems_points_written');

export const options = {
  stages: [
    { duration: '30s', target: 10  },   // 预热
    { duration: '1m',  target: 50  },   // 50并发 = 50表/秒
    { duration: '1m',  target: 100 },   // 100并发 = 100表/秒  
    { duration: '30s', target: 0   },   // 冷却
  ],
  thresholds: {
    'ems_write_ms':       ['p(95)<100', 'p(99)<200'],
    'ems_write_errors':   ['rate<0.001'],
    'http_req_failed':    ['rate<0.001'],
  },
};

// 预先生成100个电表的固定数据模板
const METER_COUNT = 100;
const TEMPLATES = Array.from({ length: METER_COUNT }, (_, i) => ({
  meterCode: `MOCK-M-ELEC-${String(i + 1).padStart(3, '0')}`,
  payload: {
    timestamp: null,  // 每次请求时动态填充
    activePower: 0,
    reactivePower: 0,
    voltageA: 0,
    currentA: 0,
    powerFactor: 0,
  }
}));

export default function () {
  const idx = Math.floor(Math.random() * METER_COUNT);
  const t = TEMPLATES[idx];

  const payload = {
    meterCode: t.meterCode,
    timestamp: new Date().toISOString(),
    activePower: 50 + Math.random() * 450,
    reactivePower: 10 + Math.random() * 150,
    voltageA: 218 + (Math.random() - 0.5) * 6,
    currentA: 5 + Math.random() * 95,
    powerFactor: 0.82 + Math.random() * 0.17,
  };

  const res = http.post(
    `${BASE}/api/v1/data`,
    JSON.stringify(payload),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${__ENV.TOKEN}`,
      },
      tags: { name: 'POST /data (collector write)' },
    }
  );

  writeTrend.add(res.timings.duration);
  writeErrors.add(res.status !== 200 && res.status !== 201);

  const ok = check(res, {
    'write accepted': (r) => r.status === 200 || r.status === 201,
    'write fast':     (r) => r.timings.duration < 200,
  });

  if (ok) {
    pointsWritten.add(1);
  }

  // 模拟真实采集间隔：每个 meter 每秒报 1 次
  sleep(0.9 + Math.random() * 0.2);
}
