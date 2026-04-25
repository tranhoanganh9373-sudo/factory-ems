// =============================================================================
// Plan 1.3 / Phase U — k6 login baseline
// =============================================================================
// A single-VU baseline against the login endpoint to detect regressions in
// the auth path (BCrypt cost, refresh-token issuance, audit write).
//
// Profile:
//   - 1 VU, 200 iterations
// Threshold:
//   - p95 < 200 ms (BCrypt cost 10 + Postgres write should comfortably fit)
//   - http_req_failed < 0.1%
//
// Usage:
//   k6 run perf/k6/auth-login.js \
//        -e BASE=http://localhost:8080 \
//        -e USER=admin \
//        -e PASS=admin123!
// =============================================================================

import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const USER = __ENV.USER || 'admin';
const PASS = __ENV.PASS || 'admin123!';

const loginTrend = new Trend('auth_login_ms', true);
const errors = new Rate('auth_login_errors');

export const options = {
  scenarios: {
    baseline: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 200,
      maxDuration: '5m',
    },
  },
  thresholds: {
    auth_login_ms: ['p(95)<200'],
    auth_login_errors: ['rate<0.001'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const res = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ username: USER, password: PASS }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }
  );

  loginTrend.add(res.timings.duration);

  const ok = check(res, {
    'login 200': (r) => r.status === 200,
    'has accessToken': (r) => {
      try {
        const b = r.json();
        return typeof b.accessToken === 'string' && b.accessToken.length > 20;
      } catch (_) {
        return false;
      }
    },
  });
  errors.add(!ok);
  if (!ok) {
    fail(`login failed status=${res.status}`);
  }
}
