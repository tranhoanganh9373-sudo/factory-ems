# Factory EMS — Performance Test Suite (k6)

Plan 1.3 / Phase U.

## Layout

```
perf/
├── k6/
│   ├── auth-login.js          # 1 VU baseline; p95 < 200 ms
│   ├── dashboard.js           # 50 VU ramp+hold; p95 < 1000 ms
│   └── report-monthly.js      # 5 VU sequential async export; p95 < 5000 ms
└── README.md                  # this file
```

## Prerequisites

- [k6](https://k6.io/) ≥ 0.50 on `$PATH`. Quick install:
  ```bash
  # macOS
  brew install k6
  # Windows (Scoop)
  scoop install k6
  # Linux (binary)
  sudo gpg -k && \
    sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
            --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 && \
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
      | sudo tee /etc/apt/sources.list.d/k6.list && \
    sudo apt-get update && sudo apt-get install k6
  ```
- factory-ems running on `$BASE` (default `http://localhost:8080`). For tests
  hitting through Nginx use `http://localhost:8888`.
- Seed data loaded — `dashboard.js` reads "today's" range so InfluxDB must
  contain entries for today; `report-monthly.js` targets `2026-04`.
- A valid admin JWT (the auth path is exercised separately by `auth-login.js`).

## Acquiring a JWT

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123!"}' \
  | jq -r '.accessToken')

# Sanity check
[ -n "$TOKEN" ] && echo "ok" || echo "login failed"
```

PowerShell equivalent:

```powershell
$body = @{ username = 'admin'; password = 'admin123!' } | ConvertTo-Json
$resp = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/auth/login' `
  -ContentType 'application/json' -Body $body
$env:TOKEN = $resp.accessToken
```

## Running the suites

```bash
# Dashboard fan-out (50 VU, ~100 s)
k6 run perf/k6/dashboard.js -e BASE=http://localhost:8080 -e TOKEN=$TOKEN

# Monthly Excel export (5 VU sequential, 25 iterations)
k6 run perf/k6/report-monthly.js -e BASE=http://localhost:8080 -e TOKEN=$TOKEN

# Login baseline (no token needed — exercises the login itself)
k6 run perf/k6/auth-login.js -e BASE=http://localhost:8080 \
  -e USER=admin -e PASS=admin123!
```

### Saving JSON results for trend analysis

```bash
mkdir -p perf/results
k6 run --out json=perf/results/dashboard-$(date +%F).json \
       perf/k6/dashboard.js -e BASE=http://localhost:8080 -e TOKEN=$TOKEN
```

## Thresholds (Plan 1.3 acceptance)

| Suite | Threshold |
|---|---|
| `dashboard.js` | p95 of `kpi`/`realtime-series`/`composition`/`top-n` < **1000 ms**, error rate < 1% |
| `report-monthly.js` | p95 end-to-end (POST → COMPLETED) < **5000 ms**, error rate < 1% |
| `auth-login.js` | p95 < **200 ms**, failure rate < 0.1% |

If any threshold fails, k6 exits with code 99 — wire that into CI to fail
performance regressions.

## Tips

- Run `dashboard.js` against Nginx (`-e BASE=http://localhost:8888`) once you
  have measured the direct-Spring numbers — the delta is your reverse-proxy
  overhead.
- For deeper diagnostics on the Spring side enable
  `management.endpoints.web.exposure.include=metrics,prometheus` and scrape
  `http_server_requests_seconds_bucket` during the run.
- Do not run the suites against production. Seeded test data only.
