import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const config = JSON.parse(open(__ENV.RUN_CONFIG));
const fixtures = new SharedArray('fixtures', () => JSON.parse(open(__ENV.FIXTURES_FILE)));
const bodies = {};
for (const [variant, batches] of Object.entries(config.templates)) {
  bodies[variant] = batches.map(batch => ({ ...batch, body: open(batch.path, 'b') }));
}
const scenarios = {};
for (const phase of config.phases) {
  const common = { startTime: `${phase.start_seconds || 0}s`, gracefulStop: `${config.graceful_seconds}s`,
    tags: { phase: phase.phase, scenario_id: config.scenario_id || config.scenario, scenario_plan: config.scenario_plan || "legacy" }, exec: 'upload' };
  if (phase.executor === 'constant-arrival-rate') {
    scenarios[phase.name] = { ...common, executor: phase.executor, rate: phase.rate,
      timeUnit: `${phase.time_unit_seconds}s`, duration: `${phase.duration}s`,
      preAllocatedVUs: phase.preallocated_vus, maxVUs: phase.max_vus };
  } else if (phase.executor === 'constant-vus') {
    scenarios[phase.name] = { ...common, executor: phase.executor, vus: phase.vus, duration: `${phase.duration}s` };
  } else {
    scenarios[phase.name] = { ...common, executor: phase.executor, vus: phase.vus,
      iterations: phase.iterations, maxDuration: `${phase.max_duration_seconds}s` };
  }
}
export const options = {
  scenarios, systemTags: ['status', 'method', 'name', 'scenario', 'check', 'error_code'],
  thresholds: { fixture_exhausted: ['count==0'] },
};
const batchMs = new Trend('http_batch_ms', true);
const journeyMs = new Trend('journey_ms', true);
const photos = new Counter('sent_photos');
const timeout = new Rate('http_timeout');
const failure = new Rate('server_failure');
const exhausted = new Counter('fixture_exhausted');
const categories = Object.fromEntries(['http_401', 'http_403', 'http_413', 'http_other_failure'].map(name => [name, new Counter(name)]));

export function upload() {
  const phase = config.phases.find(p => p.name === exec.scenario.name);
  const iteration = exec.scenario.iterationInTest;
  const fixture = fixtures[phase.offset + iteration];
  if (!fixture || iteration >= phase.count) {
    exhausted.add(1);
    exec.test.abort('independent fixtures exhausted');
    return;
  }
  const started = Date.now();
  const batches = bodies[phase.variant];
  const tags = { phase: phase.phase, scenario_id: config.scenario_id || config.scenario, scenario_plan: config.scenario_plan || "legacy" };
  for (let index = 0; index < batches.length; index++) {
    const batch = batches[index];
    const requestId = `${config.run_id}-${phase.name}-${phase.offset + iteration}-${index + 1}`;
    photos.add(batch.photo_count, tags);
    const requestStart = Date.now();
    console.log(JSON.stringify({ event: 'client_start', request_id: requestId, trip_id: fixture.trip_id,
      phase: phase.phase, phase_name: phase.name, photo_count: batch.photo_count, complete: batch.complete, start_epoch_ms: requestStart }));
    const response = http.post(`${config.base_url}/trips/${fixture.trip_id}/initial-attachments`, batch.body, {
      headers: { 'Content-Type': `multipart/form-data; boundary=${batch.boundary}`, Cookie: fixture.cookie,
        'X-CSRF-TOKEN': fixture.csrf, 'X-Request-ID': requestId },
      timeout: `${config.http_seconds}s`, tags: { ...tags, name: 'initial-attachments' },
    });
    console.log(JSON.stringify({ event: 'client_outcome', request_id: requestId, trip_id: fixture.trip_id,
      phase: phase.phase, phase_name: phase.name, photo_count: batch.photo_count, complete: batch.complete, start_epoch_ms: requestStart,
      end_epoch_ms: Date.now(), status: response.status, error_code: response.error_code }));
    const isTimeout = response.error_code === 1050;
    timeout.add(isTimeout, tags);
    let completed = false;
    try { completed = response.json('data.status') === 'COMPLETED'; } catch (_) { /* Empty 204/error body. */ }
    const success = batch.complete ? response.status === 200 && completed : response.status === 204;
    failure.add(response.status !== 0 && !success, tags);
    batchMs.add(response.timings.duration, { ...tags, outcome: isTimeout ? 'timeout' : success ? 'success' : 'failure' });
    if ([401, 403, 413].includes(response.status)) categories[`http_${response.status}`].add(1, tags);
    else if (!success && !isTimeout) categories.http_other_failure.add(1, tags);
    const expectedFailure = config.scenario === 'S7' && phase.variant !== 'recovery';
    check(response, { 'batch contract or injected failure': () => success || expectedFailure }, tags);
    // HTTP interruption does not establish a server failure. No automatic retry.
    if (!success) break;
  }
  journeyMs.add(Date.now() - started, tags);
}

export function handleSummary(data) {
  return { [config.summary_path]: JSON.stringify(data, null, 2) };
}
