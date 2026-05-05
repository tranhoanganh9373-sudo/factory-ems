// factory-ems 真实混合负载压测 — mixed-load.js
// 场景: 5人登录 + 10人看板 + 3人报表导出 + 2人CRUD (20 VU总计)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8888';
const USER = __ENV.USER || 'admin';
const PASS = __ENV.PASS || 'admin123!';

const loginTrend = new Trend('p_login_ms', true);
const meterTrend = new Trend('p_meter_ms', true);
const kpiTrend = new Trend('p_kpi_ms', true);
const seriesTrend = new Trend('p_series_ms', true);
const orgTrend = new Trend('p_org_ms', true);
const alarmTrend = new Trend('p_alarm_ms', true);
const billTrend = new Trend('p_bill_ms', true);
const tariffTrend = new Trend('p_tariff_ms', true);
const errors = new Rate('p_errors');

export const options = {
  stages: [
    { duration: '15s', target: 5  },
    { duration: '15s', target: 15 },
    { duration: '30s', target: 20 },
    { duration: '30s', target: 20 },
    { duration: '15s', target: 0  },
  ],
  thresholds: {
    'p_login_ms':   ['p(95)<400'],
    'p_meter_ms':   ['p(95)<200'],
    'p_kpi_ms':     ['p(95)<200'],
    'p_series_ms':  ['p(95)<200'],
    'p_errors':     ['rate<0.01'],
    'http_req_failed': ['rate<0.01'],
  },
};

function login() {
  const res = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ username: USER, password: PASS }),
    { headers: { 'Content-Type': 'application/json' } });
  loginTrend.add(res.timings.duration);
  if (res.status !== 200) { errors.add(1); return null; }
  try {
    return JSON.parse(res.body).data?.accessToken;
  } catch { return null; }
}

function doApi(token, method, path, trend) {
  const res = http.request(method, `${BASE}${path}`, null, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: `${method} ${path.split('?')[0]}` },
  });
  trend.add(res.timings.duration);
  if (res.status >= 400) errors.add(1);
  check(res, { '2xx': (r) => r.status < 400 });
}

export default function () {
  const token = login();
  if (!token) { sleep(0.5); return; }

  const scenario = Math.random();
  if (scenario < 0.35) {
    // 看板用户 35%
    doApi(token, 'GET', '/api/v1/dashboard/kpi?range=TODAY', kpiTrend);
    sleep(0.1);
    doApi(token, 'GET', '/api/v1/dashboard/realtime-series?range=TODAY', seriesTrend);
    sleep(0.3);
  } else if (scenario < 0.55) {
    // 仪表管理 20%
    doApi(token, 'GET', '/api/v1/meters?page=1&size=50', meterTrend);
    sleep(0.5);
  } else if (scenario < 0.70) {
    // 组织树浏览 15%
    doApi(token, 'GET', '/api/v1/org-nodes/tree', orgTrend);
    sleep(0.8);
  } else if (scenario < 0.82) {
    // 告警查看 12%
    doApi(token, 'GET', '/api/v1/alarms?page=1&size=20', alarmTrend);
    doApi(token, 'GET', '/api/v1/alarm-rules/defaults', alarmTrend);
    sleep(0.5);
  } else if (scenario < 0.92) {
    // 账单查询 10%
    doApi(token, 'GET', '/api/v1/bills/periods', billTrend);
    sleep(0.5);
  } else {
    // 电价查看 8%
    doApi(token, 'GET', '/api/v1/tariff/plans', tariffTrend);
    sleep(0.3);
  }
  sleep(1 + Math.random() * 2);
}
