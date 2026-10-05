"""Pure, bounded warmup criteria; no Docker or application configuration."""
import math
import json
import run

POLICY = dict(version=2, window_seconds=30, consecutive_windows=3, minimum_endpoint_samples=250,
              relative_latency_tolerance=.2, absolute_latency_tolerance_ms=2,
              cpu_spread_cores=.2, jit_seconds_per_window=3, flush_grace_seconds=5)


def metrics(text):
    values={'pending':0.,'jit_seconds':0.,'cpu_usage':0.};found=set()
    for line in text.splitlines():
        if not line or line.startswith('#'):continue
        name,value=line.rsplit(' ',1)
        value=float(value)
        if name.startswith('hikaricp_connections_pending'):values['pending']+=value;found.add('pending')
        elif name.startswith('jvm_compilation_time_ms_total'):values['jit_seconds']+=value/1000;found.add('jit')
        elif name=='process_cpu_usage':values['cpu_usage']=value;found.add('cpu')
    values['complete']=len(found)==3
    return values


def reasons(windows, elapsed):
    issues=[]
    if elapsed<90 or len(windows)<3:return ['need three 30-second windows']
    rows=windows[-3:]
    endpoints=set(rows[0].get('endpoint_p95',{}))
    for w in rows:
        if not w.get('complete'):issues.append('incomplete resources')
        for key in ['pending','failed','dropped']:
            if w[key]:issues.append(key)
        if not math.isfinite(w['jit_seconds']) or w['jit_seconds']<0 or w['jit_seconds']>POLICY['jit_seconds_per_window']:
            issues.append('JIT transition')
        if set(w.get('endpoint_p95',{}))!=endpoints or not endpoints:issues.append('missing endpoint')
        for name in endpoints:
            if w.get('endpoint_counts',{}).get(name,0)<POLICY['minimum_endpoint_samples']:
                issues.append(name+': insufficient samples')
    for name in ['overall',*sorted(endpoints)]:
        values=[w['p95_ms'] if name=='overall' else w.get('endpoint_p95',{}).get(name) for w in rows]
        if any(v is None or not math.isfinite(v) or v<0 for v in values):
            issues.append(name+': missing/nonfinite latency');continue
        tolerance=max(POLICY['absolute_latency_tolerance_ms'],min(values)*POLICY['relative_latency_tolerance'])
        if max(values)-min(values)>tolerance:issues.append(name+': latency spread')
    cpu=[w['cpu_cores'] for w in rows]
    if any(not math.isfinite(v) or v<0 for v in cpu) or max(cpu)-min(cpu)>POLICY['cpu_spread_cores']:
        issues.append('CPU transition')
    return sorted(set(issues))


def stable(windows, elapsed):
    return not reasons(windows,elapsed)


def ready_window_count(elapsed, start_ms, latest_event_ms):
    available=min(elapsed,(latest_event_ms-start_ms)/1000)
    return int(max(0,available-POLICY['flush_grace_seconds'])//POLICY['window_seconds'])


def read_events(path, offset=0):
    """Keep an incomplete trailing line for the next read; timestamps define bins."""
    events=[]
    if not path.exists():return events,offset
    with path.open() as f:
        f.seek(offset)
        while True:
            position=f.tell();line=f.readline()
            if not line or not line.endswith('\n'):f.seek(position);break
            event=json.loads(line)
            if event.get('type')=='Point' and event.get('metric') in {'http_req_duration','checks','dropped_iterations'}:
                event['_epoch_ms']=run.k6_epoch_ms(event['data']['time']);events.append(event)
        return events,f.tell()


def window(events, samples, start, end, endpoints):
    latencies=[];by_endpoint={name:[] for name in endpoints};failed=0;dropped=0
    for e in events:
        if e.get('type')!='Point':continue
        data=e['data'];tags=data.get('tags',{})
        epoch=e.get('_epoch_ms')
        if epoch is None:epoch=run.k6_epoch_ms(data['time'])
        if not start<=epoch<end or tags.get('phase','warmup')!='warmup':continue
        if e['metric']=='http_req_duration':
            latencies.append(data['value'])
            by_endpoint.setdefault(tags.get('name'),[]).append(data['value'])
            failed+=int(not 200<=int(tags.get('status',0))<400)
        elif e['metric']=='checks':failed+=int(data['value']!=1)
        elif e['metric']=='dropped_iterations':dropped+=data['value']
    # Bracket both exact boundaries. Include edge samples for pending/errors, and
    # interpolate cumulative CPU/JIT counters instead of using polling duration.
    before=[s for s in samples if s['epoch_ms']<=start]
    after=[s for s in samples if s['epoch_ms']>=end]
    rows=([before[-1]] if before else [])+[s for s in samples if start<s['epoch_ms']<end]+([after[0]] if after else [])
    apps=[s['application'] for s in rows if 'application' in s]
    complete=bool(before and after) and len(apps)==len(rows) and all(a.get('complete') for a in apps)
    complete=complete and not any('collection_error' in s for s in rows) and environment_complete(rows,start,end)
    def at(key,when):
        values=[(s['epoch_ms'],s.get(key) if key=='server_cpu_seconds' else s.get('application',{}).get(key)) for s in rows]
        values=[v for v in values if v[1] is not None]
        lo=[v for v in values if v[0]<=when];hi=[v for v in values if v[0]>=when]
        if not lo or not hi:return float('nan')
        lo,hi=lo[-1],hi[0]
        return lo[1] if lo[0]==hi[0] else lo[1]+(hi[1]-lo[1])*(when-lo[0])/(hi[0]-lo[0])
    return dict(start_ms=start,end_ms=end,complete=complete,
                pending=max((a.get('pending',1) for a in apps),default=1),
                jit_seconds=at('jit_seconds',end)-at('jit_seconds',start),
                cpu_cores=(at('server_cpu_seconds',end)-at('server_cpu_seconds',start))/((end-start)/1000),
                endpoint_counts={name:len(v) for name,v in by_endpoint.items()},
                endpoint_p95={name:run.percentile(v,.95) for name,v in by_endpoint.items()},
                p95_ms=run.percentile(latencies,.95),failed=failed,dropped=dropped)


def environment_complete(samples,start,end,max_gap=12):
    rows=[s for s in samples if start-12000<=s['epoch_ms']<=end+12000]
    if len(rows)<2:return False
    if rows[0]['epoch_ms']>start or rows[-1]['epoch_ms']<end:return False
    for a,b in zip(rows,rows[1:]):
        wall=(b['epoch_ms']-a['epoch_ms'])/1000
        if wall<0 or wall>max_gap:return False
        if 'monotonic' in a and 'monotonic' in b and abs(wall-(b['monotonic']-a['monotonic']))>2:return False
    return True
