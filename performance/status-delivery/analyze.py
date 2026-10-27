import json,statistics,csv,re
from pathlib import Path
R=Path(__file__).resolve().parent
results=json.loads((R/'summary.json').read_text())
def series_values(run,key):
 v=json.loads((R/'runs'/run['label']/'prometheus.json').read_text())[key]['data']['result']
 return [float(point[1]) for s in v for point in s['values'] if point[1] not in ['NaN','+Inf','-Inf']]
def mean(v):return statistics.mean(v) if v else None
for r in results:
 r['poll_rps']=r['poll_requests']/r.get('measurement_seconds',120)
 for k in ['backend_cpu','redis_ops','redis_cpu','redis_memory','hikari_active','hikari_pending']:
  r[k+'_mean']=mean(series_values(r,k))
 r['backend_cpu_sum_percent']=r['backend_cpu_mean']*200 if r['backend_cpu_mean'] is not None else None
 r['heap_each_mean_mib']=mean(series_values(r,'heap_bytes'))/(1024**2)
 r['completion_p95_ms']=r['status_delivery']['COMPLETED']['p95_ms']
 r['change_p95_ms']=r['status_delivery']['PROCESSING']['p95_ms']
 r['api_p95_ms']=r['poll_latency_ms']['p(95)']
 r['probe_p95_ms']=r['map_probe_latency_ms']['p(95)']
groups=[]
for kind in ['story','creation','update']:
 for n in [100,500,1000]:
  rs=[r for r in results if r['kind']==kind and r['vus']==n]
  if not rs:continue
  g={'kind':kind,'vus':n,'repeats':len(rs),'all_valid':all(r['exit_code']==0 and r['poll_errors']['rate']==0 and r['map_probe_errors']['rate']==0 and all(v['received_users']==n for v in r['status_delivery'].values()) for r in rs)}
  for k in ['poll_rps','api_p95_ms','probe_p95_ms','change_p95_ms','completion_p95_ms','backend_cpu_sum_percent','redis_ops_mean','redis_cpu_mean','heap_each_mean_mib','mysql_select_delta','ai_request_delta']:
   vs=[r[k] for r in rs if r[k] is not None];g[k]=statistics.median(vs) if vs else None;g[k+'_min']=min(vs) if vs else None;g[k+'_max']=max(vs) if vs else None
  groups.append(g)
stats=[]
for line in (R/'docker-stats.jsonl').read_text().splitlines():
 try:stats.append(json.loads(line))
 except ValueError:pass
def memory_mib(s):
 value=s.split('/')[0].strip();m=re.match(r'([0-9.]+)([A-Za-z]+)',value)
 if not m:return 0
 scales={'B':1/1048576,'KiB':1/1024,'MiB':1,'GiB':1024,'kB':1000/1048576,'MB':1000000/1048576,'GB':1000000000/1048576}
 return float(m[1])*scales[m[2]]
for r in results:
 meta=json.loads((R/'runs'/r['label']/'metadata.json').read_text());r['container_resources']={}
 for name in ['be1','be2','mysql','redis','ai','prometheus','redis-exporter']:
  rows=[x for x in stats if x.get('Name')=='yeodam-pollbase-'+name+'-1' and meta['measurement_start']<=x['epoch']<=meta['measurement_end']]
  if rows:r['container_resources'][name]={'samples':len(rows),'cpu_percent_mean':mean([float(x['CPUPerc'].strip('%')) for x in rows]),'memory_mib_mean':mean([memory_mib(x['MemUsage']) for x in rows]),'memory_mib_max':max(memory_mib(x['MemUsage']) for x in rows)}
(R/'analysis.json').write_text(json.dumps({'runs':results,'groups':groups},indent=2))
with (R/'summary.csv').open('w',newline='') as f:
 w=csv.DictWriter(f,fieldnames=list(groups[0]));w.writeheader();w.writerows(groups)
print(json.dumps(groups,indent=2))
