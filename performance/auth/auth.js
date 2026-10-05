import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { check } from 'k6';
import { Gauge } from 'k6/metrics';
const windowStart = new Gauge('auth_window_start_ms');

const fixtures = new SharedArray('auth-fixtures', () => JSON.parse(open(__ENV.AUTH_FIXTURES)));
const base = __ENV.AUTH_BASE;
if (!/^http:\/\/127\.0\.0\.1:\d+\/api$/.test(base)) throw new Error('Loopback server required');
const scenario = __ENV.AUTH_SCENARIO || 'S1';
const rate = Number(__ENV.AUTH_RATE || 50);
const warmup = Number(__ENV.AUTH_WARMUP || 30);
const duration = Number(__ENV.AUTH_DURATION || 60);
const maxVus = Number(__ENV.AUTH_VUS || 100);
const timeout = `${Number(__ENV.AUTH_TIMEOUT || 5)}s`;
if (!['S1', 'S3'].includes(scenario) || rate < 1 || !Number.isInteger(rate)
    || (scenario === 'S3' && rate % 2) || maxVus > fixtures.length) throw new Error('Invalid load or fixture count');
const config = (startTime, seconds, phase) => ({
  executor: 'constant-arrival-rate', rate: scenario === 'S3' ? rate / 2 : rate,
  timeUnit: '1s', duration: `${seconds}s`, startTime, preAllocatedVUs: maxVus, maxVUs: maxVus,
  gracefulStop: '10s', tags: { phase, auth_scenario: scenario },
});
export const options = {
  scenarios: {
    warmup: config('0s', warmup, 'warmup'),
    measurement: config(`${warmup + 10}s`, duration, 'measurement'),
  },
  thresholds: {
    'checks{phase:measurement}': ['rate==1'],
    'http_req_failed{phase:measurement}': ['rate==0'],
    'dropped_iterations{phase:measurement}': ['count==0'],
  },
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};
export default function () {
  const phase = exec.scenario.name;
  if (phase === 'measurement') windowStart.add(exec.scenario.startTime);
  // Write pairs keep one independent row per VU. Reads rotate across the full active input set.
  const index = scenario === 'S3' ? (exec.vu.idInTest - 1) % fixtures.length
    : exec.scenario.iterationInTest % fixtures.length;
  const f = fixtures[index];
  const params = {
    headers: { Cookie: f.cookie, 'X-CSRF-TOKEN': f.csrf,
      'X-Auth-Perf-Phase': phase, 'X-Auth-Perf-Scenario': scenario },
    tags: { phase, auth_scenario: scenario, fixture_index: String(index) }, timeout,
  };
  if (scenario === 'S1') {
    const r = http.get(`${base}/places?query=${encodeURIComponent('서울')}&pageNo=1`,
      { ...params, tags: { ...params.tags, name: 'auth-get' } });
    check(r, { 'place response': r => r.status === 200 && r.json('data') !== undefined }, { phase });
  } else {
    const url = `${base}/trips/${f.trip_id}/favorite`;
    const added = http.post(url, null, { ...params, tags: { ...params.tags, name: 'favorite-add' } });
    check(added, { 'favorite true': r => r.status === 200 && r.json('data.isFavorite') === true }, { phase });
    const removed = http.del(url, null, { ...params, tags: { ...params.tags, name: 'favorite-remove' } });
    check(removed, { 'favorite removed': r => r.status === 204 }, { phase });
  }
}
