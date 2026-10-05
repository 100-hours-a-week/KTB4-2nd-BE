#!/usr/bin/env python3
"""Bounded local auth baseline runner. Python stdlib only; no production tokens."""
import argparse
import csv
import hashlib
import shutil
import sys
import datetime
import json
import math
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.request
import urllib.error

REPO = Path(__file__).resolve().parents[2]
MYSQL = 'jdbc:mysql://127.0.0.1:13307/auth_performance?serverTimezone=Asia/Seoul&characterEncoding=utf8'


def validate_mysql(url):
    if not re.fullmatch(r'jdbc:mysql://(?:127\.0\.0\.1|localhost):[0-9]+/auth_performance(?:\?.*)?', url):
        raise ValueError('Only loopback auth_performance database is allowed')


def iteration_rate(scenario, rate):
    if scenario not in ('S1', 'S3') or rate < 1 or int(rate) != rate or (scenario == 'S3' and rate % 2):
        raise ValueError('S3 requires an even HTTP rate; S1/S3 only')
    return rate // 2 if scenario == 'S3' else rate


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def summarize(rows, start_ms, end_ms):
    measured = [r for r in rows if r['phase'] == 'measurement' and r.get('stage', 'http') == 'http']
    successful = [r for r in measured if 200 <= int(r['status']) < 300]
    completed = [r for r in successful if start_ms <= float(r['epoch_ms']) <= end_ms]
    durations = [float(r['duration_ms']) for r in successful]
    return dict(measurement_requests=len(measured), successful_requests=len(successful),
                failed_requests=len(measured) - len(successful),
                success_rps=len(completed) / ((end_ms - start_ms) / 1000),
                drain_completions=sum(float(r['epoch_ms']) > end_ms for r in measured),
                success_p50_ms=percentile(durations, .50), success_p95_ms=percentile(durations, .95),
                success_p99_ms=percentile(durations, .99) if len(successful) >= 10000 else None,
                p99_insufficient_samples=len(successful) < 10000)


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2))


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=30, **kwargs).stdout


def mysql_snapshot(container):
    query = """SELECT DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED,SUM_ERRORS
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME='auth_performance' AND DIGEST_TEXT IS NOT NULL;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Questions','Bytes_received','Bytes_sent');
SELECT TABLE_NAME,TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA='auth_performance';"""
    return command(['docker', 'exec', container, 'mysql', '-uroot', '-proot', '-B', '-e', query])


def bootstrap(container):
    # No shared schemas are cleared. Dedicated local database only.
    command(['docker', 'exec', container, 'mysql', '-uroot', '-proot', '-e',
             "CREATE DATABASE IF NOT EXISTS auth_performance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
             "GRANT ALL ON auth_performance.* TO 'yeodam'@'%';"])


def request(base, path, fixture=None, method='GET', csrf=True):
    headers = {'X-Auth-Perf-Phase': 'smoke', 'X-Auth-Perf-Scenario': 'S0'}
    if fixture:
        headers['Cookie'] = fixture['cookie']
        headers['X-CSRF-TOKEN'] = fixture['csrf'] if csrf else 'invalid'
    req = urllib.request.Request(base + path, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def smoke(base, fixtures):
    f = fixtures[0]
    checks = []
    status, body = request(base, '/places?query=%EC%84%9C%EC%9A%B8&pageNo=1', f)
    checks.append(('authenticated_get', status == 200 and 'data' in json.loads(body)))
    checks.append(('unauthenticated_get', request(base, '/places?query=%EC%84%9C%EC%9A%B8')[0] == 401))
    path = '/trips/{}/favorite'.format(f['trip_id'])
    checks.append(('bad_csrf', request(base, path, f, 'POST', False)[0] == 403))
    status, body = request(base, path, f, 'POST')
    checks.append(('favorite_add', status == 200 and json.loads(body)['data']['isFavorite'] is True))
    checks.append(('favorite_remove', request(base, path, f, 'DELETE')[0] == 204))
    if not all(ok for _, ok in checks):
        raise RuntimeError('Smoke failed: ' + repr(checks))
    return dict(checks)


def cpu_seconds(text):
    # ps TIME: [[days-]hours:]minutes:seconds, including fractional seconds.
    days, clock = (text.split('-', 1) if '-' in text else ('0', text))
    result = 0.0
    for part in clock.split(':'):
        result = result * 60 + float(part)
    return int(days) * 86400 + result


def storage_sample(container):
    # cgroup CPU counts actual CPU seconds rather than tool-specific CPU percentages.
    raw = command(['docker', 'exec', container, 'cat', '/sys/fs/cgroup/cpu.stat'])
    values = dict(line.split() for line in raw.splitlines() if len(line.split()) == 2)
    memory = command(['docker', 'exec', container, 'cat', '/sys/fs/cgroup/memory.current'])
    return dict(cpu_seconds=int(values['usage_usec']) / 1e6, memory_bytes=int(memory.strip()))


def resource_report(path, start, end, successes):
    samples = sorted((json.loads(line) for line in path.read_text().splitlines()), key=lambda s: s['epoch_ms'])
    def at(key, when):
        values = [(s['epoch_ms'], s[key]) for s in samples if key in s]
        before = [v for v in values if v[0] <= when]
        after = [v for v in values if v[0] >= when]
        if not before or not after:
            return None
        lo, hi = before[-1], after[0]
        if lo[0] == hi[0]: return lo[1]
        return lo[1] + (hi[1] - lo[1]) * (when - lo[0]) / (hi[0] - lo[0])
    result = {}
    for key in ['server_cpu_seconds', 'mysql_cpu_seconds', 'redis_cpu_seconds']:
        first, last = at(key, start), at(key, end)
        if first is not None and last is not None:
            delta = max(0, last - first)
            result[key] = delta
            result[key + '_per_success'] = delta / successes if successes else None
    for key in ['server_rss_bytes', 'server_memory_bytes', 'mysql_memory_bytes', 'redis_memory_bytes']:
        values = [s[key] for s in samples if key in s and start <= s['epoch_ms'] <= end]
        if values:
            result[key + '_mean'] = sum(values) / len(values)
            result[key + '_peak'] = max(values)
    cpu_keys = ['server_cpu_seconds', 'mysql_cpu_seconds']
    if 'redis_cpu_seconds' in result: cpu_keys.append('redis_cpu_seconds')
    if all(key in result for key in cpu_keys):
        result['total_cpu_seconds'] = sum(result[key] for key in cpu_keys)
        result['total_cpu_seconds_per_success'] = result['total_cpu_seconds'] / successes if successes else None
    result['method'] = 'Cumulative CPU interpolated at window boundaries; CPU and memory scopes differ between host process and cgroup'
    return result


def observe(stop, root, base, pid, mysql_container, redis_container):
    """Keep process and metric time series outside the server JVM."""
    with (root / 'resources.jsonl').open('a') as out:
        tick = 0
        while not stop.is_set():
            sample = {'epoch_ms': int(time.time() * 1000)}
            try:
                sample['server_ps'] = command(['ps', '-p', str(pid), '-o', 'pid=,rss=,%cpu=,time='])
                parts = sample['server_ps'].split()
                sample['server_rss_bytes'] = int(parts[1]) * 1024
                sample['server_cpu_seconds'] = cpu_seconds(parts[3])
                if tick % 5 == 0:
                    for name, container in [('mysql', mysql_container), ('redis', redis_container)]:
                        if container:
                            storage = storage_sample(container)
                            sample[name + '_cpu_seconds'] = storage['cpu_seconds']
                            sample[name + '_memory_bytes'] = storage['memory_bytes']
                    if redis_container:
                        sample['redis_info'] = command(['docker', 'exec', redis_container, 'redis-cli', 'INFO'])
                if tick % 5 == 0:
                    with urllib.request.urlopen(base + '/actuator/prometheus', timeout=3) as response:
                        metrics = response.read().decode()
                    (root / 'metrics').mkdir(exist_ok=True)
                    (root / 'metrics' / (str(sample['epoch_ms']) + '.prom')).write_text(metrics)
            except Exception as error:
                sample['collection_error'] = type(error).__name__
            out.write(json.dumps(sample) + '\n'); out.flush()
            tick += 1
            stop.wait(1)


def container_observer(root, containers):
    output = (root / 'containers.jsonl').open('w')
    process = subprocess.Popen(['docker', 'stats', '--format', '{{json .}}'] + containers,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    def collect():
        for line in process.stdout:
            try:
                output.write(json.dumps(dict(epoch_ms=int(time.time() * 1000), stats=json.loads(line))) + '\n')
                output.flush()
            except (ValueError, OSError):
                break
    worker = threading.Thread(target=collect, daemon=True); worker.start()
    return process, output, worker


def stop_container_observer(observer):
    process, output, worker = observer
    try:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    finally:
        worker.join(timeout=3)
        output.close()


def k6_epoch_ms(value):
    normalized = value.replace('Z', '+00:00')
    normalized = re.sub(r'\.(\d+)(?=[+-]\d{2}:\d{2}$)',
                        lambda m: '.' + m.group(1).ljust(6, '0')[:6], normalized)
    return datetime.datetime.fromisoformat(normalized).timestamp() * 1000


def parse_report(root, before_index, duration, server_csv=None):
    raw = root / 'k6-raw.json'
    start = None
    client = []
    accessed = set()
    client_rows = []
    attempted = 0
    with raw.open() as stream:
        for line in stream:
            event = json.loads(line)
            if event.get('type') != 'Point':
                continue
            data = event['data']; tags = data.get('tags', {})
            if event['metric'] == 'auth_window_start_ms':
                start = data['value']
            if event['metric'] == 'auth_fixture_index' and tags.get('phase')=='measurement':
                accessed.add(int(data['value']))
            if event['metric'] == 'http_req_duration' and tags.get('phase') == 'measurement':
                attempted += 1
                if 'fixture_index' in tags: accessed.add(tags['fixture_index'])
                epoch = k6_epoch_ms(data['time'])
                client_rows.append(dict(phase='measurement', epoch_ms=epoch, status=int(tags.get('status', 0)), duration_ms=data['value']))
                if 200 <= int(tags.get('status', 0)) < 300: client.append(data['value'])
    if start is None:
        raise RuntimeError('No measurement start marker in k6 output')
    with (server_csv or root.parent / 'server-requests.csv').open() as f:
        rows = list(csv.DictReader(f))[before_index:]
    report = summarize(rows, start, start + duration * 1000)
    client_report = summarize(client_rows, start, start + duration * 1000)
    report['server_success_rps'] = report['success_rps']
    report['success_rps'] = client_report['success_rps']
    report['client_drain_completions'] = client_report['drain_completions']
    report['client_failed_requests'] = client_report['failed_requests']
    report['client_success_p95_ms'] = percentile(client, .95)
    report['client_success_samples'] = len(client)
    report['client_measurement_requests'] = attempted
    report['actual_accessed_inputs'] = len(accessed)
    report['window_start_ms'] = start
    report['window_end_ms'] = start + duration * 1000
    report['stages'] = {}
    n = report['measurement_requests']
    for stage in sorted({r['stage'] for r in rows if r['stage'] != 'http'}):
        group = [r for r in rows if r['phase'] == 'measurement' and r['stage'] == stage]
        report['stages'][stage] = dict(calls=sum(int(r['calls']) for r in group),
            stage_p95_ms=percentile([float(r['stage_ms']) for r in group], .95),
            sql_count=sum(int(r['sql_count']) for r in group),
            sql_ms=sum(float(r['sql_ms']) for r in group),
            sql_per_request=sum(int(r['sql_count']) for r in group) / n if n else None)
    return report


def load(args, root, base, fixtures_path, scenario, rate, warmup, duration, label):
    iteration_rate(scenario, rate)
    case = root / label; case.mkdir()
    # Writer flushes every 200ms. It is idle before each bounded case.
    with (root / 'server-requests.csv').open() as f:
        before_index = max(0, sum(1 for _ in f) - 1)
    (case / 'mysql-before.tsv').write_text(mysql_snapshot(args.mysql_container))
    env = dict(os.environ, AUTH_BASE=base, AUTH_FIXTURES=str(fixtures_path), AUTH_SCENARIO=scenario,
               AUTH_RATE=str(rate), AUTH_VUS=str(args.vus), AUTH_WARMUP=str(warmup), AUTH_DURATION=str(duration))
    started = time.time()
    with (case / 'k6.log').open('w') as log:
        result = subprocess.run(['k6', 'run', '--quiet', '--out', 'json=' + str(case / 'k6-raw.json'),
                '--summary-export', str(case / 'k6-summary.json'), str(Path(__file__).with_name('auth.js'))],
                env=env, stdout=log, stderr=subprocess.STDOUT, timeout=warmup + duration + 35)
    time.sleep(.4)
    idle = json.loads((root / 'idle.json').read_text())
    if idle['active_requests']:
        raise RuntimeError('Server still active; stop load and preserve data')
    (case / 'mysql-after.tsv').write_text(mysql_snapshot(args.mysql_container))
    # A final storage sample brackets the window even when measurement is shorter than the 5s polling period.
    boundary = dict(epoch_ms=int(time.time() * 1000))
    parts = command(['ps', '-p', str(args.server_pid), '-o', 'pid=,rss=,%cpu=,time=']).split()
    boundary['server_cpu_seconds'] = cpu_seconds(parts[3]); boundary['server_rss_bytes'] = int(parts[1]) * 1024
    for name, container in [('mysql', args.mysql_container), ('redis', args.redis_container)]:
        if container:
            snapshot = storage_sample(container)
            boundary[name + '_cpu_seconds'] = snapshot['cpu_seconds']
            boundary[name + '_memory_bytes'] = snapshot['memory_bytes']
    with (root / 'resources.jsonl').open('a') as out:
        out.write(json.dumps(boundary) + '\n')
    report = parse_report(case, before_index, duration)
    report.update(scenario=scenario, requested_http_rps=rate, k6_exit=result.returncode,
                  wall_seconds=time.time() - started, variant=args.variant)
    summary = json.loads((case / 'k6-summary.json').read_text())['metrics']
    report['dropped_iterations'] = summary.get('dropped_iterations{phase:measurement}', summary.get('dropped_iterations', {})).get('count', 0)
    report['resources'] = resource_report(root / 'resources.jsonl', report['window_start_ms'], report['window_end_ms'],
                                           report['success_rps'] * duration)
    report['valid'] = report['client_measurement_requests'] == report['measurement_requests'] and result.returncode == 0 and report['measurement_requests'] > 0 and report['failed_requests'] == 0 and report['client_failed_requests'] == 0 and report['dropped_iterations'] == 0
    save(case / 'report.json', report)
    print(label, json.dumps({k: report[k] for k in ['valid', 'success_rps', 'success_p95_ms', 'client_success_p95_ms', 'dropped_iterations']}), flush=True)
    if not report['valid']:
        raise RuntimeError('Invalid case; no further load scheduled. Inspect preserved report.')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['smoke', 'verify', 'discover', 'measure'])
    parser.add_argument('--users', type=int, default=10000)
    parser.add_argument('--active-users', type=int, default=1000)
    parser.add_argument('--vus', type=int, default=100)
    parser.add_argument('--rate', type=int, default=50)
    parser.add_argument('--write-rate', type=int, default=50)
    parser.add_argument('--variant', default='A')
    parser.add_argument('--scenario', choices=['S1', 'S3', 'both'], default='both')
    parser.add_argument('--repetitions', type=int, choices=range(1, 6), default=2)
    parser.add_argument('--dataset', default='baseline')
    parser.add_argument('--mysql', default=MYSQL)
    parser.add_argument('--mysql-container', default='yeodam-performance-mysql-1')
    parser.add_argument('--redis-container', help='Optional Redis container name for later comparison')
    parser.add_argument('--init-db', action='store_true')
    args = parser.parse_args()
    validate_mysql(args.mysql)
    if not re.fullmatch('[a-zA-Z0-9-]{1,24}', args.variant) or not re.fullmatch('[a-z0-9-]{1,24}', args.dataset):
        parser.error('Invalid variant/dataset label')
    if not 1 <= args.vus <= args.active_users <= args.users <= 100000:
        parser.error('Require 1 <= VUs <= active users <= users <= 100000')
    iteration_rate('S1', args.rate); iteration_rate('S3', args.write_rate)
    if args.init_db:
        bootstrap(args.mysql_container)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    root = REPO.parent / 'performance-results' / 'auth' / (stamp + '-' + args.variant + '-' + args.mode)
    root.mkdir(parents=True, mode=0o700)
    os.chmod(root, 0o700)
    fixture = root / 'fixtures.json'
    stop = threading.Event(); server = None; containers = None; observer = None
    try:
        log = (root / 'server.log').open('w')
        server = subprocess.Popen(['./gradlew', 'authPerformanceServerTest',
            '-Pload.runDir=' + str(root), '-Pload.mysql=' + args.mysql, '-Pload.dataset=' + args.dataset,
            '-Pload.users=' + str(args.users), '-Pload.activeUsers=' + str(args.active_users), '-Pload.budgetSeconds=1200'],
            cwd=REPO, stdout=log, stderr=subprocess.STDOUT,
            env=dict(os.environ, AWS_EC2_METADATA_DISABLED='true', AWS_ACCESS_KEY_ID='test', AWS_SECRET_ACCESS_KEY='test'))
        deadline = time.monotonic() + 240
        while not (root / 'ready.json').exists():
            if server.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Server bootstrap failed; inspect server.log')
            time.sleep(.25)
        ready = json.loads((root / 'ready.json').read_text()); base = 'http://127.0.0.1:{}/api'.format(ready['port'])
        args.server_pid = ready['pid']
        inputs = json.loads(fixture.read_text())
        save(root / 'smoke.json', smoke(base, inputs))
        manifest = dict(ready, mode=args.mode, variant=args.variant,
            git_commit=command(['git', 'rev-parse', 'HEAD'], cwd=REPO).strip(),
            dirty=command(['git', 'status', '--short'], cwd=REPO),
            k6_version=command(['k6', 'version']).strip(),
            java_version=subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.strip(),
            started_utc=stamp, read_rate=args.rate, write_rate=args.write_rate, vus=args.vus,
            scenarios=args.scenario, repetitions=args.repetitions,
            duration_seconds=60 if args.mode == 'measure' else 2 if args.mode == 'verify' else 20,
            heap='-Xms1g -Xmx1g', hikari_max=10, resources_scope='Host JVM + Docker storage; no CPU quotas added')
        sources = list((REPO / 'performance' / 'auth').glob('*.py')) + list((REPO / 'performance' / 'auth').glob('*.js'))
        sources += list((REPO / 'src/performanceTest/java/com/yeodam/yeodambe/user/performance').glob('*.java'))
        sources += [REPO / 'build.gradle']
        sources += list((REPO / 'src/main/java/com/yeodam/yeodambe/user').rglob('*.java'))
        sources += [REPO / 'src/main/resources/application.yaml']
        manifest['source_hashes'] = {}
        for source in sources:
            relative = source.relative_to(REPO)
            manifest['source_hashes'][str(relative)] = hashlib.sha256(source.read_bytes()).hexdigest()
            target = root / 'source' / relative; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        manifest['mysql_container'] = command(['docker', 'inspect', '--format',
            '{{.Config.Image}} {{.Image}} NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}} Cpuset={{.HostConfig.CpusetCpus}}', args.mysql_container]).strip()
        manifest['mysql_version'] = command(['docker', 'exec', args.mysql_container, 'mysql', '-uroot', '-proot', '-N', '-e', 'SELECT VERSION()']).strip()
        manifest['docker_vm'] = command(['docker', 'info', '--format', '{{.NCPU}} CPUs / {{.MemTotal}} bytes']).strip()
        if args.redis_container:
            manifest['session_store'] = 'Redis LoginSessionStore; member and CSRF remain RDB'
            manifest['redis_key_prefix'] = 'yeodam:performance:authperf:' + args.dataset + ':'
            manifest['redis_container'] = command(['docker', 'inspect', '--format',
                '{{.Config.Image}} {{.Image}} NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}} Cmd={{json .Config.Cmd}}', args.redis_container]).strip()
            manifest['redis_version'] = command(['docker', 'exec', args.redis_container, 'redis-cli', 'INFO', 'server'])
        save(root / 'manifest.json', manifest)
        (root / 'implementation.patch').write_text(command(['git', 'diff'], cwd=REPO))
        observer = threading.Thread(target=observe, args=(stop, root, base, ready['pid'], args.mysql_container, args.redis_container), daemon=True); observer.start()
        containers = container_observer(root, [args.mysql_container] + ([args.redis_container] if args.redis_container else []))
        reports = []
        if args.mode == 'verify':
            for scenario in ['S1', 'S3']:
                reports.append(load(args, root, base, fixture, scenario, 2, 1, 2, scenario + '-verify'))
        elif args.mode == 'discover':
            for scenario in ['S1', 'S3']:
                for rate in [26, 50, 100, 200] if scenario == 'S3' else [25, 50, 100, 200]:
                    reports.append(load(args, root, base, fixture, scenario, rate, 30 if rate <= 26 else 1, 20, scenario + '-' + str(rate)))
        elif args.mode == 'measure':
            for scenario, rate in [('S1', args.rate), ('S3', args.write_rate)]:
                if args.scenario != 'both' and args.scenario != scenario: continue
                for repetition in range(1, args.repetitions + 1):
                    reports.append(load(args, root, base, fixture, scenario, rate, 30, 60, scenario + '-' + str(repetition)))
        save(root / 'reports.json', reports)
        print('Results:', root, flush=True)
    finally:
        if server is not None:
            save(root / 'stop.json', {'stop': True})
            try: server.wait(timeout=30)
            except subprocess.TimeoutExpired:
                server.terminate()
                save(root / 'incomplete.json', {'server_stop_timeout': True, 'fixtures_preserved': True})
            if server.returncode != 0:
                save(root / 'incomplete.json', {'server_exit': server.returncode, 'fixtures_preserved': True})
        stop.set()
        if observer: observer.join(timeout=5)
        try:
            if containers: stop_container_observer(containers)
        finally:
            fixture.unlink(missing_ok=True)
            fixture.with_name('fixtures.json.tmp').unlink(missing_ok=True)
        if server is not None: log.close()
        if (root / 'incomplete.json').exists() and sys.exc_info()[0] is None:
            raise RuntimeError('Server did not stop cleanly; see incomplete.json')

if __name__ == '__main__':
    main()
