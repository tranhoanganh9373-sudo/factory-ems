/**
 * Bill period setup fixture.
 *
 * Ensures that a SUCCESS cost_allocation_run exists that covers the UTC
 * boundaries of the given YearMonth bill period so that
 * `PUT /api/v1/bills/periods/{id}/close` does not return 409.
 *
 * The backend (BillingServiceImpl) stores period boundaries in Asia/Shanghai
 * (UTC+8), so 2026-03 → periodStart = 2026-02-28T16:00:00Z,
 *                        periodEnd   = 2026-03-31T16:00:00Z.
 *
 * The coverage query in CostAllocationRunRepository is:
 *   r.periodStart <= :start AND r.periodEnd >= :end
 *
 * We compute the same boundaries and submit a cost run that matches exactly.
 */

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:8888';
const POLL_INTERVAL_MS = 2_000;
const POLL_TIMEOUT_MS = 60_000;

/** Login as admin and return the access token. */
async function adminToken(): Promise<string> {
  const res = await fetch(`${BASE_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123!' }),
  });
  if (!res.ok) throw new Error(`Login failed: HTTP ${res.status}`);
  const body = await res.json();
  const token: string | undefined = body?.data?.accessToken;
  if (!token) throw new Error(`Login response missing accessToken: ${JSON.stringify(body)}`);
  return token;
}

/**
 * Compute the UTC boundary strings for a YearMonth bill period.
 *
 * Backend uses ZoneOffset.ofHours(8):
 *   periodStart = ym.atDay(1).atStartOfDay().atOffset(+8) → stored as UTC
 *   periodEnd   = ym.plusMonths(1).atDay(1).atStartOfDay().atOffset(+8) → UTC
 *
 * Example: ym="2026-03"
 *   periodStart = 2026-03-01T00:00:00+08:00 = 2026-02-28T16:00:00Z
 *   periodEnd   = 2026-04-01T00:00:00+08:00 = 2026-03-31T16:00:00Z
 */
function periodBoundaries(ym: string): { periodStart: string; periodEnd: string } {
  const [year, month] = ym.split('-').map(Number);
  // Subtract 8 hours from local midnight to get UTC equivalent
  const startUtc = new Date(Date.UTC(year, month - 1, 1, 0, 0, 0) - 8 * 3600 * 1000);
  const endUtc   = new Date(Date.UTC(year, month,     1, 0, 0, 0) - 8 * 3600 * 1000);
  return {
    periodStart: startUtc.toISOString().replace('.000Z', 'Z'),
    periodEnd:   endUtc.toISOString().replace('.000Z', 'Z'),
  };
}

/** Return the id of the first enabled cost rule, or throw if none exist. */
async function firstEnabledRuleId(token: string): Promise<number> {
  const res = await fetch(`${BASE_URL}/api/v1/cost/rules`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`GET /cost/rules failed: HTTP ${res.status}`);
  const body = await res.json();
  const rules: Array<{ id: number; enabled: boolean }> = body?.data ?? [];
  const enabled = rules.find((r) => r.enabled);
  if (!enabled) throw new Error('No enabled cost rules found — cannot seed cost-allocation run');
  return enabled.id;
}

/** Submit a cost-allocation run and return its runId. */
async function submitRun(
  token: string,
  periodStart: string,
  periodEnd: string,
  ruleId: number,
): Promise<number> {
  const res = await fetch(`${BASE_URL}/api/v1/cost/runs`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ periodStart, periodEnd, ruleIds: [ruleId] }),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`POST /cost/runs failed: HTTP ${res.status} — ${text}`);
  }
  const body = await res.json();
  const runId: number | undefined = body?.data?.runId;
  if (!runId) throw new Error(`POST /cost/runs response missing runId: ${JSON.stringify(body)}`);
  return runId;
}

/** Poll run/{runId} until status is SUCCESS or FAILED, or timeout. */
async function pollUntilSuccess(token: string, runId: number): Promise<void> {
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const res = await fetch(`${BASE_URL}/api/v1/cost/runs/${runId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`GET /cost/runs/${runId} failed: HTTP ${res.status}`);
    const body = await res.json();
    const status: string = body?.data?.status;
    if (status === 'SUCCESS') return;
    if (status === 'FAILED') {
      const msg: string = body?.data?.errorMessage ?? '(no error message)';
      throw new Error(`Cost run ${runId} FAILED: ${msg}`);
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  throw new Error(`Cost run ${runId} did not reach SUCCESS within ${POLL_TIMEOUT_MS}ms`);
}

/**
 * Ensure a SUCCESS cost_allocation_run covers the bill period for `ym`.
 *
 * Idempotent — always submits a new run (cheap, completes in <1s on the
 * test environment).  Call this in `test.beforeAll` before any UI action
 * that closes the bill period.
 *
 * @param ym  Year-month string, e.g. "2026-03"
 */
export async function ensureCostRunSuccess(ym: string): Promise<void> {
  const token = await adminToken();
  const { periodStart, periodEnd } = periodBoundaries(ym);
  const ruleId = await firstEnabledRuleId(token);
  const runId = await submitRun(token, periodStart, periodEnd, ruleId);
  await pollUntilSuccess(token, runId);
}

/**
 * Ensure the bill period row exists for `ym` (creates it via API if absent).
 * Returns the period id.
 *
 * Optional — the UI spec also creates the period; this just guarantees the
 * row exists before `ensureCostRunSuccess` is called.
 *
 * @param ym  Year-month string, e.g. "2026-03"
 */
export async function ensureBillPeriod(ym: string): Promise<number> {
  const token = await adminToken();
  const res = await fetch(`${BASE_URL}/api/v1/bills/periods?ym=${ym}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`POST /bills/periods?ym=${ym} failed: HTTP ${res.status} — ${text}`);
  }
  const body = await res.json();
  const id: number | undefined = body?.data?.id;
  if (!id) throw new Error(`ensureBillPeriod: missing id in response: ${JSON.stringify(body)}`);
  return id;
}

/**
 * Full prerequisite chain for specs that only need a closed period to exist
 * (e.g. cost-export) without exercising the UI close flow themselves:
 *   1. Ensure bill period row exists → get id
 *   2. Ensure a SUCCESS cost run covers it
 *   3. Close the period via API (idempotent — CLOSED→CLOSED regenerates bills)
 *
 * Skips close only when period is already LOCKED (cannot reopen without unlock).
 *
 * @param ym  Year-month string, e.g. "2026-03"
 */
export async function ensurePeriodClosed(ym: string): Promise<void> {
  const token = await adminToken();

  // Step 1: ensure period row exists
  const periodId = await ensureBillPeriodWithToken(token, ym);

  // Step 2: check current status via GET /bills/periods/{ym} — skip close if LOCKED
  const statusRes = await fetch(`${BASE_URL}/api/v1/bills/periods/${ym}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!statusRes.ok) throw new Error(`GET /bills/periods/${ym} failed: HTTP ${statusRes.status}`);
  const statusBody = await statusRes.json();
  const currentStatus: string = statusBody?.data?.status ?? '';
  if (currentStatus === 'LOCKED') return; // already locked — bills exist

  // Step 3: ensure SUCCESS cost run
  const { periodStart, periodEnd } = periodBoundaries(ym);
  const ruleId = await firstEnabledRuleId(token);
  const runId = await submitRun(token, periodStart, periodEnd, ruleId);
  await pollUntilSuccess(token, runId);

  // Step 4: close via API
  const closeRes = await fetch(`${BASE_URL}/api/v1/bills/periods/${periodId}/close`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!closeRes.ok) {
    const text = await closeRes.text();
    throw new Error(`PUT /bills/periods/${periodId}/close failed: HTTP ${closeRes.status} — ${text}`);
  }
}

/** Internal helper: create-or-get period using a pre-existing token. */
async function ensureBillPeriodWithToken(token: string, ym: string): Promise<number> {
  const res = await fetch(`${BASE_URL}/api/v1/bills/periods?ym=${ym}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`POST /bills/periods?ym=${ym} failed: HTTP ${res.status} — ${text}`);
  }
  const body = await res.json();
  const id: number | undefined = body?.data?.id;
  if (!id) throw new Error(`ensureBillPeriodWithToken: missing id in response: ${JSON.stringify(body)}`);
  return id;
}
