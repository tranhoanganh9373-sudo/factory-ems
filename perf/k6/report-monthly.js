// =============================================================================
// Plan 1.3 / Phase U — k6 monthly Excel export
// =============================================================================
// Simulates 5 concurrent admins kicking off month-aggregated Excel exports.
// Each VU:
//   1. POST /api/v1/reports/export → returns { fileToken, status: PENDING|RUNNING }
//   2. Polls GET /api/v1/reports/export/{token} every 1 s
//      until status COMPLETED (or FAILED) — measures end-to-end latency.
//   3. Issues GET /api/v1/reports/export/{token}/download to verify the file
//      is fetchable (counts toward end-to-end timing only as "download
//      readiness"; the actual download bytes are not transferred to keep
//      the test loopback-friendly).
//
// Profile:
//   - 5 VU sequential (each VU runs the full POST→poll→download chain once
//     per iteration)
//   - 5 iterations per VU = 25 total exports
// Threshold:
//   - p95 end-to-end (POST → COMPLETED status) < 5000 ms
//   - error rate < 1%
//
// Usage:
//   k6 run perf/k6/report-monthly.js \
//        -e BASE=http://localhost:8080 \
//        -e TOKEN=$JWT
// =============================================================================

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

if (!TOKEN) {
  throw new Error('Missing TOKEN env. Run: -e TOKEN=$JWT');
}

const HEADERS = {
  Authorization: `Bearer ${TOKEN}`,
  'Content-Type': 'application/json',
};

const e2eTrend = new Trend('report_export_e2e_ms', true);
const errors = new Rate('report_export_errors');

export const options = {
  scenarios: {
    monthly: {
      executor: 'per-vu-iterations',
      vus: 5,
      iterations: 5,
      maxDuration: '5m',
    },
  },
  thresholds: {
    // Plan 1.3 acceptance: end-to-end < 5000 ms.
    report_export_e2e_ms: ['p(95)<5000'],
    report_export_errors: ['rate<0.01'],
  },
};

function targetMonthRange() {
  // 2026-04 is the seeded reference month per Plan 1.3 acceptance.
  return { from: '2026-04-01T00:00:00Z', to: '2026-04-30T23:59:59Z' };
}

export default function () {
  const { from, to } = targetMonthRange();
  const t0 = Date.now();

  // ── 1. Kick off async export ──
  const submit = http.post(
    `${BASE}/api/v1/reports/export`,
    JSON.stringify({
      preset: 'MONTHLY',
      from,
      to,
      granularity: 'DAY',
      format: 'XLSX',
    }),
    { headers: HEADERS, tags: { phase: 'submit' } }
  );
  if (
    !check(submit, {
      'submit 202 or 200': (r) => r.status === 202 || r.status === 200,
    })
  ) {
    errors.add(true);
    fail(`submit failed: status=${submit.status} body=${submit.body}`);
  }

  let body;
  try {
    body = submit.json();
  } catch (e) {
    errors.add(true);
    fail(`submit body not json: ${submit.body}`);
  }
  const token = body.fileToken || body.token;
  if (!token) {
    errors.add(true);
    fail(`no fileToken in submit response: ${JSON.stringify(body)}`);
  }

  // ── 2. Poll until COMPLETED ──
  const deadline = t0 + 30_000; // hard ceiling 30 s; threshold is 5 s
  let finalStatus = '';
  while (Date.now() < deadline) {
    const status = http.get(`${BASE}/api/v1/reports/export/${token}`, {
      headers: HEADERS,
      tags: { phase: 'poll' },
    });
    if (status.status !== 200) {
      errors.add(true);
      break;
    }
    let s;
    try {
      s = status.json();
    } catch (_) {
      break;
    }
    finalStatus = s.status || '';
    if (finalStatus === 'COMPLETED' || finalStatus === 'READY') break;
    if (finalStatus === 'FAILED' || finalStatus === 'CANCELLED') break;
    sleep(1);
  }

  const e2e = Date.now() - t0;
  e2eTrend.add(e2e);

  const ok =
    check({ status: finalStatus }, {
      'reached COMPLETED/READY': (r) => r.status === 'COMPLETED' || r.status === 'READY',
    });
  errors.add(!ok);

  // ── 3. Verify download is fetchable (HEAD if backend supports, else GET) ──
  if (ok) {
    const download = http.get(`${BASE}/api/v1/reports/export/${token}/download`, {
      headers: HEADERS,
      tags: { phase: 'download' },
    });
    check(download, { 'download 200': (r) => r.status === 200 });
  }
}
