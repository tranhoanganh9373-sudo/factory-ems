// =============================================================================
// Plan 1.3 / Phase U — k6 dashboard load test
// =============================================================================
// Hits the four read-heavy dashboard endpoints simultaneously, simulating a
// real user who opens the home page and immediately sees panels ①–⑨ render
// in parallel.
//
// Profile:
//   - 30 s ramp-up to 50 VU
//   - 60 s sustained at 50 VU
//   - 10 s ramp-down
// Threshold:
//   - p95 across the four endpoints < 1000 ms
//   - error rate < 1%
//
// Usage:
//   k6 run perf/k6/dashboard.js \
//        -e BASE=http://localhost:8080 \
//        -e TOKEN=$JWT
//
// Acquire JWT once:
//   curl -s -X POST http://localhost:8080/api/v1/auth/login \
//        -H 'Content-Type: application/json' \
//        -d '{"username":"admin","password":"admin123!"}' | jq -r '.accessToken'
// =============================================================================

import http from 'k6/http';
import { check, group, sleep } from 'k6';
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

// Per-endpoint custom trends so we can see which one dominates p95.
const kpiTrend = new Trend('dashboard_kpi_ms', true);
const seriesTrend = new Trend('dashboard_series_ms', true);
const compositionTrend = new Trend('dashboard_composition_ms', true);
const topnTrend = new Trend('dashboard_topn_ms', true);
const errors = new Rate('dashboard_errors');

export const options = {
  stages: [
    { duration: '30s', target: 50 }, // ramp
    { duration: '60s', target: 50 }, // hold
    { duration: '10s', target: 0 }, // ramp down
  ],
  thresholds: {
    // Plan 1.3 acceptance: p95 < 1000 ms across all dashboard reads.
    http_req_duration: ['p(95)<1000'],
    dashboard_kpi_ms: ['p(95)<1000'],
    dashboard_series_ms: ['p(95)<1000'],
    dashboard_composition_ms: ['p(95)<1000'],
    dashboard_topn_ms: ['p(95)<1000'],
    dashboard_errors: ['rate<0.01'],
  },
};

function todayRange() {
  const now = new Date();
  const yyyy = now.getUTCFullYear();
  const mm = String(now.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(now.getUTCDate()).padStart(2, '0');
  const day = `${yyyy}-${mm}-${dd}`;
  return { from: `${day}T00:00:00Z`, to: `${day}T23:59:59Z` };
}

export default function () {
  const { from, to } = todayRange();
  const qs = `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&granularity=HOUR`;

  group('dashboard read fanout', () => {
    const responses = http.batch([
      ['GET', `${BASE}/api/v1/dashboard/kpi?${qs}`, null, { headers: HEADERS, tags: { name: 'kpi' } }],
      ['GET', `${BASE}/api/v1/dashboard/realtime-series?${qs}`, null, { headers: HEADERS, tags: { name: 'series' } }],
      ['GET', `${BASE}/api/v1/dashboard/composition?${qs}`, null, { headers: HEADERS, tags: { name: 'composition' } }],
      ['GET', `${BASE}/api/v1/dashboard/top-n?${qs}&n=10`, null, { headers: HEADERS, tags: { name: 'topn' } }],
    ]);

    const [kpi, series, comp, topn] = responses;

    kpiTrend.add(kpi.timings.duration);
    seriesTrend.add(series.timings.duration);
    compositionTrend.add(comp.timings.duration);
    topnTrend.add(topn.timings.duration);

    const ok =
      check(kpi, { 'kpi 200': (r) => r.status === 200 }) &&
      check(series, { 'series 200': (r) => r.status === 200 }) &&
      check(comp, { 'composition 200': (r) => r.status === 200 }) &&
      check(topn, { 'topn 200': (r) => r.status === 200 });
    errors.add(!ok);
  });

  // Mimic user think time before next refresh.
  sleep(1);
}
