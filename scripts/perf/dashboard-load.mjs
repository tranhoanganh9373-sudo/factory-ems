#!/usr/bin/env node
/**
 * dashboard-load.mjs — Node.js fallback for dashboard concurrency test.
 * Used by dashboard-load.sh when `hey` is not available.
 *
 * Requirements: Node 18+ (native fetch) or Node 16+ with --experimental-fetch.
 * No external npm packages needed.
 *
 * Usage (called by dashboard-load.sh, not directly):
 *   node dashboard-load.mjs <base_url> <token> <concurrency> <total>
 *
 * Outputs JSON to stdout:
 *   { p50, p90, p95, p99, mean, errorRate, totalRequests, errors }
 */

'use strict';

const [,, BASE_URL, TOKEN, CONCURRENCY_STR, TOTAL_STR] = process.argv;

if (!BASE_URL || !TOKEN) {
  process.stderr.write('Usage: node dashboard-load.mjs <base_url> <token> <concurrency> <total>\n');
  process.exit(2);
}

const CONCURRENCY = parseInt(CONCURRENCY_STR || '50', 10);
const TOTAL       = parseInt(TOTAL_STR       || '5000', 10);

const ENDPOINTS = [
  '/api/v1/dashboard/kpi?range=TODAY',
  '/api/v1/dashboard/realtime-series?range=LAST_24H',
];

const headers = {
  Authorization: `Bearer ${TOKEN}`,
  Accept: 'application/json',
};

// Simple semaphore using a counter + promise queue
function createSemaphore(n) {
  let count = 0;
  const queue = [];
  return {
    acquire() {
      if (count < n) {
        count++;
        return Promise.resolve();
      }
      return new Promise((resolve) => queue.push(resolve));
    },
    release() {
      if (queue.length > 0) {
        const next = queue.shift();
        next();
      } else {
        count--;
      }
    },
  };
}

async function request(url) {
  const start = Date.now();
  try {
    const res = await fetch(url, { headers });
    const ms = Date.now() - start;
    return { ms, ok: res.ok, status: res.status };
  } catch (err) {
    const ms = Date.now() - start;
    return { ms, ok: false, status: 0, error: String(err) };
  }
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, idx)];
}

async function main() {
  const sem = createSemaphore(CONCURRENCY);
  const latencies = [];
  let errors = 0;

  const tasks = Array.from({ length: TOTAL }, (_, i) => {
    const endpoint = ENDPOINTS[i % ENDPOINTS.length];
    const url = BASE_URL.replace(/\/$/, '') + endpoint;
    return async () => {
      await sem.acquire();
      try {
        const result = await request(url);
        latencies.push(result.ms);
        if (!result.ok) errors++;
      } finally {
        sem.release();
      }
    };
  });

  process.stderr.write(`Starting ${TOTAL} requests, concurrency=${CONCURRENCY}\n`);
  const wallStart = Date.now();
  await Promise.all(tasks.map((t) => t()));
  const wallMs = Date.now() - wallStart;

  const sorted = latencies.slice().sort((a, b) => a - b);
  const mean = sorted.length > 0
    ? Math.round(sorted.reduce((s, v) => s + v, 0) / sorted.length)
    : 0;

  const result = {
    totalRequests: TOTAL,
    errors,
    errorRate: TOTAL > 0 ? (errors / TOTAL) : 0,
    mean,
    p50: percentile(sorted, 50),
    p90: percentile(sorted, 90),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    wallMs,
  };

  process.stdout.write(JSON.stringify(result) + '\n');
}

main().catch((err) => {
  process.stderr.write(`Fatal: ${err}\n`);
  process.exit(1);
});
