/**
 * k6 load test for the push layer.
 *
 * Simulates N concurrent viewers holding an SSE connection, and measures what actually matters
 * for a live map: how long a client waits between position updates, how big those updates are,
 * and how long the server takes from computing a position to delivering it (the `sentAt` and
 * `generatedAt` fields of every message).
 *
 *   k6 run infra/k6/push-load.js                       # 50 viewers for 2 minutes
 *   k6 run -e VUS=500 -e DURATION=5m infra/k6/push-load.js
 *   k6 run -e BASE_URL=https://example.org infra/k6/push-load.js
 *
 * k6 has no EventSource, so the stream is read as a plain HTTP response body: `http.get` with
 * a response callback streams chunks as they arrive. Results are written to summary.json and
 * printed; docs/MEASURING.md explains how to turn them into the resume numbers.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '2m';

// Time from the server computing a position to the client receiving the message.
const pushLag = new Trend('tracknyc_push_lag_ms', true);
// Gap between consecutive messages at one client: should track the flush interval.
const updateGap = new Trend('tracknyc_update_gap_ms', true);
const snapshotBytes = new Trend('tracknyc_snapshot_bytes');
const diffBytes = new Trend('tracknyc_diff_bytes');
const diffsReceived = new Counter('tracknyc_diffs_received');
const trainsPerDiff = new Trend('tracknyc_trains_per_diff');
const sequenceGaps = new Counter('tracknyc_sequence_gaps');
const connectOk = new Rate('tracknyc_connect_success');

export const options = {
  scenarios: {
    viewers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    // Push lag is the headline number: p95 under 2 s on top of the 5 s flush interval.
    tracknyc_push_lag_ms: ['p(95)<7000'],
    tracknyc_connect_success: ['rate>0.99'],
    // A client should never miss a diff; a gap forces a reconnect and a fresh snapshot.
    tracknyc_sequence_gaps: ['count<1'],
    http_req_failed: ['rate<0.01'],
  },
  discardResponseBodies: false,
};

function handleEvent(state, block) {
  let event = 'message';
  const dataLines = [];
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  }
  if (dataLines.length === 0) return;
  const raw = dataLines.join('\n');
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch (e) {
    return;
  }
  const now = Date.now();
  if (event === 'snapshot') {
    snapshotBytes.add(raw.length);
    state.seq = msg.seq;
    state.lastAt = now;
  } else if (event === 'diff') {
    diffsReceived.add(1);
    diffBytes.add(raw.length);
    trainsPerDiff.add((msg.upserts || []).length + (msg.removes || []).length);
    if (state.seq >= 0 && msg.seq !== state.seq + 1) sequenceGaps.add(1);
    state.seq = msg.seq;
    if (msg.generatedAt) pushLag.add(now - Date.parse(msg.generatedAt));
    if (state.lastAt) updateGap.add(now - state.lastAt);
    state.lastAt = now;
  }
}

export default function () {
  const state = { seq: -1, lastAt: 0, buffer: '' };
  const res = http.get(`${BASE_URL}/api/stream/trains`, {
    headers: { Accept: 'text/event-stream' },
    timeout: '70s',
    responseCallback: http.expectedStatuses(200),
    // Read the stream for a minute, then disconnect and reconnect like a real viewer would.
    responseType: 'text',
  });
  connectOk.add(res.status === 200);
  check(res, { 'stream opened': (r) => r.status === 200 });
  if (typeof res.body === 'string') {
    state.buffer = res.body;
    for (const block of state.buffer.split('\n\n')) {
      if (block.trim()) handleEvent(state, block);
    }
  }
}

export function handleSummary(data) {
  return {
    'summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const p = (name, stat) => (m[name] && m[name].values[stat] != null ? m[name].values[stat].toFixed(0) : 'n/a');
  return [
    '',
    'TrackNYC push-layer load test',
    '-----------------------------',
    `viewers (peak)        ${VUS}`,
    `connections opened    ${p('tracknyc_connect_success', 'passes')}`,
    `diffs received        ${p('tracknyc_diffs_received', 'count')}`,
    `sequence gaps         ${p('tracknyc_sequence_gaps', 'count')}`,
    `push lag  p50 / p95   ${p('tracknyc_push_lag_ms', 'p(50)')} ms / ${p('tracknyc_push_lag_ms', 'p(95)')} ms`,
    `update gap p50        ${p('tracknyc_update_gap_ms', 'p(50)')} ms`,
    `snapshot bytes p95    ${p('tracknyc_snapshot_bytes', 'p(95)')}`,
    `diff bytes p95        ${p('tracknyc_diff_bytes', 'p(95)')}`,
    `trains per diff p95   ${p('tracknyc_trains_per_diff', 'p(95)')}`,
    '',
    'Fill docs/MEASURING.md with these numbers; do not quote them before running this.',
    '',
  ].join('\n');
}
