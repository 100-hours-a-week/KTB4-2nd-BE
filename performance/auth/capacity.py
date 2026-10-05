#!/usr/bin/env python3
"""Equal-budget mixed auth/RTR capacity runner; host k6, container-only server."""
import argparse
import csv
import datetime
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import threading
import signal
import time
import urllib.request
import urllib.error
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import run
import stabilization
import faults

MIX = {'place': .5, 'me': .2, 'favorite-add': .1, 'favorite-remove': .1, 'refresh': .1}
PROFILES={'normal':MIX,'db':dict(place=.2,me=.2,trips=.3,**{'favorite-add':.1,'favorite-remove':.1,'refresh':.1}),
          'rtr-burst':MIX}
COMPOSE = Path(__file__).with_name('compose.capacity.yml')


# B remains the historical alias of B1; numbered variants describe storage, not repetitions.
STORES = {'A': ('rdb', 'rdb'), 'B': ('redis', 'rdb'),
          'B1': ('redis', 'rdb'), 'B2': ('redis', 'redis')}


def validate_stores(variant, metadata):
    session, csrf = STORES[variant]
    if metadata.get('store') != session or metadata.get('csrfStore') != csrf:
        raise ValueError('Variant does not match compiled session/CSRF stores; rebuild distribution')


def csrf_evidence_matches(variant, stages):
    csrf = stages.get('csrf', {})
    if csrf.get('calls', 0) == 0:
        return False
    if STORES[variant][1] == 'rdb':
        return csrf.get('sql_count', 0) > 0
    return csrf.get('sql_count', 0) == 0 and stages.get('csrf-redis-command', {}).get('calls', 0) > 0


def assess(report, p95_limit_ms):
    n = report['client_measurement_requests']
    mix=PROFILES[report.get('profile','normal')]
    # Burst foreground retains the original mix; spike traffic is assessed separately.
    counts=report.get('foreground_endpoints',report['endpoints']);count=sum(counts.values())
    distribution = count > 0 and all(abs(counts.get(k,0)/count-fraction)<=max(.01,1/count)
                                     for k,fraction in mix.items())
    valid = (n == report['measurement_requests'] and n > 0
             and report['resources_complete'] and report['lifecycle_complete'])
    warm=report.get('stabilization',{'required':False})
    warmed=not warm.get('required') or warm.get('passed',False)
    valid = valid and distribution and warmed and report.get('environment_complete',True)
    reasons = []
    if not valid: reasons.append('incomplete request/resource/lifecycle evidence')
    if not distribution: reasons.append('HTTP mix differs by more than 1 percentage point')
    if not warmed:reasons.append('Initial warmup did not stabilize')
    if not report.get('environment_complete',True):reasons.append('Environment clock/observation discontinuity')
    if report['success_rps'] < report['requested_http_rps'] * .99: reasons.append('completed load below 99%')
    if report['client_failed_requests'] or report['failed_checks']: reasons.append('HTTP/check failure')
    if report['dropped_iterations']: reasons.append('dropped iterations; inspect VU/generator/SUT')
    for key in ['client_success_p95_ms', 'refresh_p95_ms']:
        if report.get(key) is None or report[key] > p95_limit_ms: reasons.append(key + ' exceeds limit')
    return dict(valid=valid,sustainable=not reasons,reasons=reasons,classification='environment-invalid' if not valid else 'overload' if reasons else 'sustainable',drop_cause='SUT/VU/generator diagnosis required' if report['dropped_iterations'] else None)


def search(probe, start=100, ceiling=3200, precision=100, step_seconds=60):
    low, high, rate = 0, None, start
    while True:
        report = probe(rate, step_seconds)
        if not report['valid']: raise RuntimeError('Invalid probe; cannot infer capacity')
        if report['sustainable']:
            low = rate
            if rate == ceiling: break
            rate = min(rate * 2, ceiling)
        else:
            high = rate; break
    while high is not None and high - low > precision:
        rate = ((low + high) // (2 * 10)) * 10
        report = probe(rate, step_seconds)
        if not report['valid']: raise RuntimeError('Invalid probe; cannot infer capacity')
        if report['sustainable']: low = rate
        else: high = rate
    if low:
        for _ in range(2):
            report = probe(low, max(180, int(10000 / (low * .7)) + 10))
            if not report['valid']: raise RuntimeError('Invalid confirmation')
            if not report['sustainable'] or not report.get('coverage_complete', True):
                return dict(confirmed_rps=None, candidate_rps=low, failed_upper_rps=high,
                            censored=high is None, confirmation_failed=True)
    return dict(confirmed_rps=low or None, failed_upper_rps=high, censored=high is None,
                confirmation_failed=False)


def validate_comparison(a, b):
    for key in ['total_cpu', 'total_memory', 'active_users', 'mix', 'p95_limit_ms', 'profile', 'redis_persistence', 'warmup_policy']:
        if a.get(key) != b.get(key): raise ValueError('Comparison differs: ' + key)


def compose(env, *args):
    return run.command(['docker', 'compose', '-p', 'yeodam-auth-capacity', '-f', str(COMPOSE),
                        '--profile', 'redis', *args], env=env)


def container_id(env, service):
    value = compose(env, 'ps', '-q', service).strip()
    if not value: raise RuntimeError('Missing container: ' + service)
    return value


def inspect_limits(container, cpu, memory):
    info = json.loads(run.command(['docker', 'inspect', container]))[0]
    config = info['HostConfig']
    if config['NanoCpus'] != int(cpu * 1e9) or config['Memory'] != memory:
        raise RuntimeError('Resource quota differs: ' + info['Name'])
    return dict(image=info['Image'], cpu=cpu, memory=memory)


def snapshot(containers):
    result = {'epoch_ms': int(time.time() * 1000),'monotonic':time.monotonic(),'host_load':os.getloadavg()}
    for name, container in containers.items():
        sample = run.storage_sample(container)
        result[name + '_cpu_seconds'] = sample['cpu_seconds']
        result[name + '_memory_bytes'] = sample['memory_bytes']
        result[name + '_cpu_stat'] = run.command(['docker', 'exec', container, 'cat', '/sys/fs/cgroup/cpu.stat'])
        result[name + '_memory_events'] = run.command(['docker', 'exec', container, 'cat', '/sys/fs/cgroup/memory.events'])
    return result


def observe(stop, root, base, containers, generator, interval=5):
    tick = 0
    while not stop.is_set():
        try:
            sample = snapshot(containers)
            if tick%3==0:
                sample['host_memory']=run.command(['vm_stat'])
                sample['all_containers']=run.command(['docker','stats','--no-stream','--format','{{json .}}'])
            if generator.get('pid'):
                process = subprocess.run(['ps','-p',str(generator['pid']),'-o','pid=,rss=,%cpu=,time='],capture_output=True,text=True)
                if process.returncode == 0: sample['generator_ps']=process.stdout.strip()
            if 'redis' in containers:
                sample['redis_info'] = run.command(['docker', 'exec', containers['redis'], 'redis-cli', 'INFO','all'])
            with urllib.request.urlopen(base + '/actuator/prometheus', timeout=3) as r:
                prom=r.read().decode(); (root / ('metrics-' + str(tick) + '.prom')).write_text(prom)
                sample['application']=stabilization.metrics(prom)
        except Exception as e:
            sample = dict(epoch_ms=int(time.time()*1000), collection_error=type(e).__name__)
        with (root / 'resources.jsonl').open('a') as f: f.write(json.dumps(sample) + '\n')
        tick += 1; stop.wait(interval)


def http_request(base, path, f, jar, method='GET', cookie=None, timeout=10, phase='smoke'):
    headers = {'X-CSRF-TOKEN': f['csrf'],'X-Auth-Perf-Phase':phase,'X-Auth-Perf-Scenario':'MIX',
               'X-Auth-Perf-Endpoint':'refresh' if path=='/auth/token/refresh' else 'me' if path=='/users/me' else 'unknown'}
    if cookie is not None: headers['Cookie'] = cookie
    request = urllib.request.Request(base + path, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    try:
        with opener.open(request, timeout=timeout) as response: return response.status, response.read()
    except urllib.error.HTTPError as error: return error.code, error.read()


def rtr_smoke(base, inputs, fixture_path):
    f = inputs[-1]
    jar = http.cookiejar.CookieJar()
    initial = f['cookie'] + '; refreshToken=' + f['refresh_token']
    status, _ = http_request(base, '/auth/token/refresh', f, jar, 'POST', initial)
    if status != 200: raise RuntimeError('Initial RTR smoke failed: ' + str(status))
    cookies = {c.name: c.value for c in jar}
    if not {'accessToken', 'refreshToken'} <= cookies.keys(): raise RuntimeError('Missing RTR cookies')
    # Reusing an old token must fail; protected access using the new JWT must succeed.
    if http_request(base, '/auth/token/refresh', f, http.cookiejar.CookieJar(), 'POST', initial)[0] != 401:
        raise RuntimeError('Old refresh token was accepted')
    if http_request(base, '/users/me', f, jar, cookie='CSRF_CONTEXT=' + initial.split('CSRF_CONTEXT=')[1].split(';')[0]
                    + '; accessToken=' + cookies['accessToken'])[0] != 200:
        raise RuntimeError('Rotated access token was rejected')
    context = initial.split('CSRF_CONTEXT=')[1].split(';')[0]
    rotated = 'CSRF_CONTEXT=' + context + '; refreshToken=' + cookies['refreshToken']
    def consume(_):
        own = http.cookiejar.CookieJar()
        status, _ = http_request(base, '/auth/token/refresh', f, own, 'POST', rotated)
        return status, {c.name:c.value for c in own}
    with ThreadPoolExecutor(max_workers=2) as pool: responses = list(pool.map(consume, range(2)))
    if sorted(status for status,_ in responses) != [200,401]: raise RuntimeError('Concurrent RTR must succeed once')
    current = next(c for status,c in responses if status == 200)
    f['cookie'] = 'accessToken=' + current['accessToken'] + '; CSRF_CONTEXT=' + context
    f['refresh_token'] = current['refreshToken']
    fixture_path.write_text(json.dumps(inputs)); os.chmod(fixture_path, 0o600)
    return dict(initial=True, old_rejected=True, new_access=True, concurrent_single_success=True)


def parse_mixed(case, duration):
    report = run.parse_report(case, 0, duration, case / 'server-requests.csv')
    counts, foreground, failures = Counter(), Counter(), 0
    refresh_inputs=set(); endpoints={}
    with (case / 'k6-raw.json').open() as raw:
        for line in raw:
            event = json.loads(line)
            if event.get('type') != 'Point': continue
            data=event['data']; tags=data.get('tags',{})
            if tags.get('phase') != 'measurement': continue
            name=tags.get('name')
            if event['metric']=='auth_fixture_index' and name=='refresh':refresh_inputs.add(int(data['value']))
            if event['metric']=='checks' and data['value']!=1:failures+=1
            if event['metric']!='http_req_duration':continue
            counts[name]+=1
            if not tags.get('scenario','').startswith('refresh-spike'):foreground[name]+=1
            if name=='refresh' and 'fixture_index' in tags:refresh_inputs.add(int(tags['fixture_index']))
            entry=endpoints.setdefault(name,dict(latencies=[],statuses=Counter(),completed=0,drain=0))
            status=int(tags.get('status',0));entry['statuses'][status]+=1
            completed=run.k6_epoch_ms(data['time'])
            if 200<=status<400:
                entry['latencies'].append(data['value'])
                if report['window_start_ms']<=completed<report['window_end_ms']:entry['completed']+=1
                else:entry['drain']+=1
    for entry in endpoints.values():
        values=entry.pop('latencies')
        entry.update(p50_ms=run.percentile(values,.5),p95_ms=run.percentile(values,.95),p99_ms=run.percentile(values,.99),
                     success_rps=entry['completed']/duration)
        entry['statuses']=dict(entry['statuses'])
    report.update(endpoints=dict(counts),foreground_endpoints=dict(foreground),endpoint_metrics=endpoints,
                  refresh_p95_ms=endpoints.get('refresh',{}).get('p95_ms'),
                  failed_checks=failures,actual_refresh_inputs=len(refresh_inputs))
    by_endpoint={}
    with (case/'server-requests.csv').open() as f:
        for row in csv.DictReader(f):
            if row['phase']!='measurement' or row['stage']=='http':continue
            stage=by_endpoint.setdefault(row.get('endpoint','unknown'),{}).setdefault(row['stage'],dict(calls=0,ms=0,sql_count=0,sql_ms=0))
            for key,column in [('calls','calls'),('ms','stage_ms'),('sql_count','sql_count'),('sql_ms','sql_ms')]:stage[key]+=float(row[column])
    report['server_endpoint_stages']=by_endpoint
    report['stage_per_endpoint_request']={name:{stage:{key:value/counts.get(name,1) for key,value in values.items()} for stage,values in stages.items()} for name,stages in by_endpoint.items()}
    return report


def reset_fixture(case):
    nonce=str(time.time_ns());(case/'reset-request.json').write_text(nonce)
    deadline=time.monotonic()+180
    while True:
        ready=case/'reset-ready.json'
        if ready.exists() and json.loads(ready.read_text()).get('nonce')==nonce:break
        if time.monotonic()>deadline:raise RuntimeError('Fixture reset timeout')
        time.sleep(.2)


def stabilize(args, case, base, containers, kenv):
    folder=case/'warmup';folder.mkdir()
    stop=threading.Event();generator={};windows=[]
    observer=threading.Thread(target=observe,args=(stop,folder,base,containers,generator,2),daemon=True);observer.start()
    env=dict(kenv,AUTH_PHASE='warmup',AUTH_RATE='100',AUTH_WARMUP='0',AUTH_DURATION=str(args.warmup_max_seconds))
    passed=False;process=None;started=time.monotonic();start_ms=int(time.time()*1000);offset=0;events=[]
    try:
        with (folder/'k6.log').open('w') as log:
            process=subprocess.Popen(['k6','run','--quiet','--out','json='+str(folder/'k6-raw.json'),
                                     str(Path(__file__).with_name('mixed.js'))],env=env,stdout=log,stderr=subprocess.STDOUT)
            generator['pid']=process.pid
            last_count=0;latest_event_ms=start_ms
            while process.poll() is None and time.monotonic()-started<args.warmup_max_seconds:
                time.sleep(1)
                elapsed=time.monotonic()-started
                # Give k6 JSON output time to flush, then use event timestamps.
                new,offset=stabilization.read_events(folder/'k6-raw.json',offset);events.extend(new)
                latest=max((e['_epoch_ms'] for e in new),default=start_ms)
                if new:latest_event_ms=max(latest_event_ms,latest)
                count=stabilization.ready_window_count(elapsed,start_ms,latest_event_ms)
                if count<=last_count:continue
                resources=folder/'resources.jsonl'
                text=resources.read_text() if resources.exists() else ''
                samples=[json.loads(line) for line in text.rsplit('\n',1)[0].splitlines()]
                width=stabilization.POLICY['window_seconds']*1000
                # Recompute earlier bins too: a delayed record must not shift into
                # the next bin or be discarded after a previous evaluation.
                windows=[stabilization.window(events,samples,start_ms+i*width,start_ms+(i+1)*width,
                                               set(PROFILES[args.profile])) for i in range(count)]
                last_count=count
                issues=stabilization.reasons(windows,elapsed)
                passed=elapsed<=args.warmup_max_seconds and not issues
                run.save(case/'warmup.json',dict(required=True,passed=passed,elapsed_seconds=elapsed,
                         policy=stabilization.POLICY,start_ms=start_ms,latest_event_ms=latest_event_ms,reasons=issues,windows=windows))
                if passed:break
            if process.poll() is None:process.send_signal(signal.SIGINT)
            process.wait(timeout=20)
    finally:
        if process and process.poll() is None:process.kill();process.wait()
        stop.set();observer.join(timeout=15)
    # Recheck closed bins after k6 drains/flushing ends. Late errors must be able
    # to invalidate a provisional pass rather than disappear at early stop.
    new,offset=stabilization.read_events(folder/'k6-raw.json',offset);events.extend(new)
    resources=folder/'resources.jsonl'
    text=resources.read_text() if resources.exists() else ''
    samples=[json.loads(line) for line in text.rsplit('\n',1)[0].splitlines()]
    windows=[stabilization.window(events,samples,w['start_ms'],w['end_ms'],set(PROFILES[args.profile]))
             for w in windows]
    passed=passed and stabilization.stable(windows,time.monotonic()-started)
    result=dict(required=True,passed=passed,elapsed_seconds=time.monotonic()-started,policy=stabilization.POLICY,start_ms=start_ms,
                reasons=stabilization.reasons(windows,time.monotonic()-started),windows=windows)
    run.save(case/'warmup.json',result)
    if not passed:raise RuntimeError('Warmup did not stabilize; no capacity result produced')
    # Same JVM, fresh deterministic token state; no reuse of consumed warmup tokens.
    reset_fixture(case)
    return result


def probe(args, session, rate, duration):
    # Restore deterministic fixture in the private DB/namespace, then run a fresh JVM for every case.
    label = f'{len(session["reports"])+1:02d}-fault-{args.fault}' if args.mode=='fault' else f'{len(session["reports"])+1:02d}-{rate}rps-{duration}s'
    root = session['root']; case = root / label; case.mkdir(mode=0o700)
    env = dict(session['env'], AUTH_RESULTS=str(case))
    containers = {}; observer = None; stop = threading.Event(); finished = False
    try:
        compose(env, 'up', '-d', '--no-deps', '--force-recreate', 'auth-backend')
        containers = dict(session['storage'], server=container_id(env, 'auth-backend'))
        backend=inspect_limits(containers['server'], 2, 1536*1024**2)
        manifest=json.loads((root/'manifest.json').read_text())
        manifest['backend_image']=backend['image']; manifest['backend_limits']=backend
        run.save(root/'manifest.json',manifest)
        deadline = time.monotonic()+300
        while not (case / 'ready.json').exists():
            if time.monotonic()>deadline: raise RuntimeError('Backend ready timeout')
            state=json.loads(run.command(['docker','inspect',containers['server']]))[0]['State']
            if not state['Running']: raise RuntimeError('Backend exited during fixture setup')
            time.sleep(.5)
        fixture=case/'fixtures.json'; inputs=json.loads(fixture.read_text()); base=session['base']
        run.save(case/'smoke.json',dict(normal=run.smoke(base, inputs),rtr=rtr_smoke(base, inputs, fixture)))
        if args.mode=='fault':
            if args.fault=='all':
                reports=[];kinds=[kind for kind in faults.FAULTS if kind!='csrf' or args.variant=='B2'] if args.variant!='A' else ['multi-device','ttl','mysql-outage']
                for kind in kinds:
                    reset_fixture(case);inputs=json.loads(fixture.read_text())
                    args.fault=kind
                    reports.append(faults.execute(args,session,case,inputs,containers,strict=False))
                report=dict(passed=all(r['passed'] for r in reports),capacity_evidence=False,faults=reports)
                run.save(case/'faults.json',report)
            else:report=faults.execute(args,session,case,inputs,containers)
            run.save(case/'stop.json',dict(stop=True))
            exit_code=int(run.command(['docker','wait',containers['server']]).strip())
            finished=exit_code==0 and (case/'validation.json').exists()
            if not finished:raise RuntimeError('Fault server did not drain')
            session['reports'].append(report);run.save(root/'reports.json',session['reports'])
            return report
        kenv=dict(os.environ,AUTH_BASE=base,AUTH_FIXTURES=str(fixture),AUTH_RATE=str(rate),AUTH_VUS=str(args.vus),
                  AUTH_PROFILE=args.profile,AUTH_VERIFY_RTR='1' if args.mode=='verify' else '0')
        warm=dict(required=False,passed=False,note='Functional verification only')
        if args.mode!='verify' or args.verify_stabilization:warm=stabilize(args,case,base,containers,kenv)
        (case/'mysql-before.tsv').write_text(run.mysql_snapshot(session['storage']['mysql']))
        (case/'resources.jsonl').write_text(json.dumps(snapshot(containers))+'\n')
        generator={}
        observer=threading.Thread(target=observe,args=(stop,case,base,containers,generator,1 if args.mode=='verify' else 5),daemon=True); observer.start()
        kenv.update(AUTH_WARMUP='1' if args.mode=='verify' else '30',AUTH_DURATION=str(duration))
        with (case/'k6.log').open('w') as log:
            process=subprocess.Popen(['k6','run','--quiet','--out','json='+str(case/'k6-raw.json'),
                    '--summary-export',str(case/'k6-summary.json'),str(Path(__file__).with_name('mixed.js'))],
                    env=kenv,stdout=log,stderr=subprocess.STDOUT)
            generator['pid']=process.pid
            try: k6_exit=process.wait(timeout=duration+65)
            except BaseException:
                process.kill(); process.wait(); raise
            generator.clear()
        stop.set(); observer.join(timeout=15)
        with (case/'resources.jsonl').open('a') as f: f.write(json.dumps(snapshot(containers))+'\n')
        run.save(case/'stop.json',dict(stop=True))
        exit_code=int(run.command(['docker','wait',containers['server']]).strip())
        finished=exit_code==0 and (case/'validation.json').exists()
        report=parse_mixed(case,duration)
        summary=json.loads((case/'k6-summary.json').read_text())['metrics']
        report['dropped_iterations']=summary.get('dropped_iterations',{}).get('count',0)
        report['requested_http_rps']=rate*(1+.2*30/duration) if args.profile=='rtr-burst' and args.mode!='verify' else rate
        report['foreground_requested_http_rps']=rate
        report['profile']=args.profile;report['stabilization']=warm
        report['resources']=run.resource_report(case/'resources.jsonl',report['window_start_ms'],report['window_end_ms'],report['success_rps']*duration)
        report['resources_complete']=all(name+'_cpu_seconds' in report['resources'] and name+'_memory_bytes_peak' in report['resources'] for name in containers)
        resource_samples=[json.loads(line) for line in (case/'resources.jsonl').read_text().splitlines()]
        report['collection_errors']=sum('collection_error' in sample for sample in resource_samples)
        report['resources_complete']=report['resources_complete'] and report['collection_errors']==0
        report['environment_complete']=stabilization.environment_complete(resource_samples,report['window_start_ms'],report['window_end_ms'])
        redis_memory=[]
        for sample in resource_samples:
            if report['window_start_ms']<=sample['epoch_ms']<=report['window_end_ms']:
                for line in sample.get('redis_info','').splitlines():
                    if line.startswith('used_memory:'):redis_memory.append(int(line.split(':')[1]))
        report['redis_used_memory_peak_bytes']=max(redis_memory) if redis_memory else None
        report['redis_bytes_per_semantic_session']=max(redis_memory)/20000 if redis_memory else None
        report['lifecycle_complete']=finished
        report['k6_exit']=k6_exit
        report['actual_accessed_user_ratio']=report['actual_accessed_inputs']/10000
        report['coverage_complete']=report['actual_accessed_inputs']==10000
        report['case']=label; report['variant']=args.variant
        report.update(assess(report,args.p95_limit_ms))
        report['valid']=report['valid'] and k6_exit==0
        session_sql=report['stages'].get('session',{}).get('sql_count',0)
        if (args.variant=='A' and session_sql==0) or (args.variant!='A' and session_sql!=0):
            report['valid']=False; report['sustainable']=False; report['reasons'].append('Runtime store does not match variant')
        if not csrf_evidence_matches(args.variant, report['stages']):
            report['valid']=False; report['sustainable']=False; report['reasons'].append('Missing or mismatched runtime CSRF evidence')
        report['physical_state']=json.loads((case/'physical-state.json').read_text())
        (case/'mysql-after.tsv').write_text(run.mysql_snapshot(session['storage']['mysql']))
        run.save(case/'report.json',report); session['reports'].append(report)
        run.save(root/'reports.json',session['reports'])
        print(label,json.dumps({k:report[k] for k in ['valid','sustainable','success_rps','refresh_p95_ms','actual_accessed_inputs','reasons']}),flush=True)
        return report
    finally:
        stop.set()
        if observer: observer.join(timeout=15)
        if 'server' in containers:
            (case/'server.log').write_text(run.command(['docker','logs',containers['server']]))
            if not finished: run.save(case/'incomplete.json',dict(reason='backend/probe incomplete'))
        compose(env,'stop','-t','10','auth-backend')
        (case/'fixtures.json').unlink(missing_ok=True)
        (case/'fixtures.json.tmp').unlink(missing_ok=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['verify','measure','capacity','fault'])
    p.add_argument('--variant',choices=list(STORES),required=True)
    p.add_argument('--fault',choices=['all']+faults.FAULTS,default='multi-device')
    p.add_argument('--profile',choices=list(PROFILES),default='normal')
    p.add_argument('--verify-stabilization',action='store_true',help='Exercise initial stabilization/reset during verify; not performance evidence')
    p.add_argument('--warmup-max-seconds',type=int,default=300)
    p.add_argument('--redis-persistence',choices=['off','aof'],default='off')
    p.add_argument('--rate',type=int,default=100)
    p.add_argument('--ceiling',type=int,default=3200)
    p.add_argument('--vus',type=int,default=1000)
    p.add_argument('--p95-limit-ms',type=float,default=200)
    p.add_argument('--port',type=int,default=18081)
    p.add_argument('--java-image',default='eclipse-temurin:25-jre-noble')
    p.add_argument('--distribution',type=Path,default=run.REPO/'build/auth-capacity')
    args=p.parse_args()
    if args.rate < 10 or args.rate % 10 or args.ceiling < args.rate or args.ceiling % 10 or not 1<=args.vus<=10000:
        p.error('Rates must be multiples of 10; ceiling>=rate; 1<=VUs<=10000')
    if args.mode=='fault' and args.variant=='A' and args.fault.startswith('redis-'):p.error('Redis fault requires variant B')
    if args.mode=='capacity' and args.profile=='rtr-burst':p.error('Use fixed measure for RTR pulses; capacity probes are steady loads')
    if not 95<=args.warmup_max_seconds<=300:p.error('Warmup budget must be 95..300 seconds (90s evidence + 5s flush grace)')
    if args.profile=='rtr-burst' and args.vus+3*min(args.vus,100)>1666:p.error('Burst partitions require total allocated VUs<=1666')
    if not 1024<=args.port<=65535 or args.p95_limit_ms<=0: p.error('Invalid port/latency limit')
    if not (args.distribution/'classes').exists(): p.error('Build authCapacityDistribution first')
    metadata=json.loads((args.distribution/'implementation.json').read_text())
    try: validate_stores(args.variant, metadata)
    except ValueError as error: p.error(str(error))
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    root=run.REPO.parent/'performance-results/auth-capacity'/f'{stamp}-{args.variant}-{args.mode}'
    root.mkdir(parents=True,mode=0o700)
    env=dict(os.environ,AUTH_MYSQL_CPUS='2' if args.variant=='A' else '1.5',
             AUTH_MYSQL_MEMORY='2560m' if args.variant=='A' else '2048m',
             AUTH_STORE=STORES[args.variant][0], AUTH_CSRF_STORE=STORES[args.variant][1], AUTH_PORT=str(args.port),
             AUTH_DATASET='capacity',AUTH_DISTRIBUTION=str(args.distribution.resolve()),
             AUTH_RESULTS=str(root),AUTH_JAVA_IMAGE=args.java_image,AUTH_REDIS_AOF='yes' if args.redis_persistence=='aof' else 'no')
    reports=[];session=dict(root=root,env=env,reports=reports,base=f'http://127.0.0.1:{args.port}/api')
    try:
        compose(env,'stop','auth-backend','auth-redis')
        services=['auth-mysql']+(['auth-redis'] if args.variant!='A' else [])
        compose(env,'up','-d','--wait',*services)
        storage={'mysql':container_id(env,'auth-mysql')}
        limits={'mysql':inspect_limits(storage['mysql'],2 if args.variant=='A' else 1.5,(2560 if args.variant=='A' else 2048)*1024**2)}
        if args.variant!='A':
            storage['redis']=container_id(env,'auth-redis');limits['redis']=inspect_limits(storage['redis'],.5,512*1024**2)
        session['storage']=storage
        (root/'mysql-durability.tsv').write_text(run.command(['docker','exec',storage['mysql'],'mysql','-uroot','-proot','-B','-e',
            "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('innodb_flush_log_at_trx_commit','sync_binlog','innodb_buffer_pool_size')"]))
        manifest=dict(variant=args.variant,mode=args.mode,warmup_policy=stabilization.POLICY,total_cpu=4,total_memory=4*1024**3,
            active_users=10000,users=10000,sessions=20000,mix=PROFILES[args.profile],profile=args.profile,spike_schedule=([dict(start_seconds=20+i*30,duration_seconds=10,extra_rtr_rps=args.rate*.2) for i in range(3)] if args.profile=='rtr-burst' else []),spike_vus=min(args.vus,100),redis_persistence=args.redis_persistence,warmup_max_seconds=args.warmup_max_seconds,p95_limit_ms=args.p95_limit_ms,
            implementation=metadata,limits=limits,vus=args.vus,heap='1GiB',hikari=10,rate=args.rate,ceiling=args.ceiling,
            docker_vm=run.command(['docker','info','--format','{{.NCPU}} CPUs / {{.MemTotal}} bytes']).strip(),
            other_containers=run.command(['docker','ps','--format','{{json .}}']),
            k6_version=run.command(['k6','version']).strip(),
            source_hashes={str(f.relative_to(args.distribution)):hashlib.sha256(f.read_bytes()).hexdigest()
                           for f in args.distribution.rglob('*') if f.is_file()},
            harness_hashes={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__),COMPOSE,Path(__file__).with_name('mixed.js'),Path(__file__).with_name('run.py'),Path(__file__).with_name('stabilization.py'),Path(__file__).with_name('faults.py')]})
        run.save(root/'manifest.json',manifest)
        if args.mode=='capacity':
            result=search(lambda rate,seconds:probe(args,session,rate,seconds),args.rate,args.ceiling,100,60)
            run.save(root/'capacity.json',result)
        else:
            seconds=(16 if args.profile=='rtr-burst' else 10) if args.mode=='verify' else max(180,int(10000/(args.rate*.7))+10)
            report=probe(args,session,20 if args.mode=='verify' else args.rate,seconds)
            if args.mode=='fault' and not report['passed']:raise RuntimeError('Fault contract mismatch; reports preserved, production unchanged')
            if args.mode!='fault' and (not report['valid'] or (args.mode=='verify' and (report['client_failed_requests'] or report['failed_checks']))):
                raise RuntimeError('Invalid verification/load; inspect preserved report')
        run.save(root/'validation.json',dict(complete=True))
        print('Results:',root)
    finally:
        compose(env,'stop','-t','10','auth-backend','auth-mysql','auth-redis')

if __name__=='__main__':main()
