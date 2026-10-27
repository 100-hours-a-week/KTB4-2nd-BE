import os,json,time,subprocess,urllib.request,urllib.parse,hashlib,hmac,base64,threading,statistics,sys
from pathlib import Path
from fixture import generate
ROOT=Path(__file__).resolve().parent
DC=['docker','compose','-p','yeodam-pollbase','-f',str(ROOT/'compose.json')]
def sql(q):
 return subprocess.run(DC+['exec','-T','-e','MYSQL_PWD=yeodam','mysql','mysql','-uyeodam','-N','-B','yeodam'],input=q,text=True,capture_output=True,check=True).stdout

def get(url):
 with urllib.request.urlopen(url,timeout=15) as r:return json.load(r)
def prom(path,params):return get('http://127.0.0.1:19090/api/v1/'+path+'?'+urllib.parse.urlencode(params))
def statdb():
 rows=sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_select','Questions','Threads_connected','Threads_running');")
 return {s.split('\t')[0]:int(s.split('\t')[1]) for s in rows.strip().splitlines()}
def jwt(i):
 enc=lambda b:base64.urlsafe_b64encode(b).decode().rstrip('=')
 now=int(time.time());head=enc(b'{"alg":"HS256","typ":"JWT"}');payload=enc(json.dumps({'iss':'http://polling.local','aud':['yeodam-api'],'iat':now,'exp':now+1800,'sub':str(10000+i),'sid':f'poll-session-{i}'},separators=(',',':')).encode());data=head+'.'+payload
 return data+'.'+enc(hmac.new(b'local-development-jwt-secret-key-32-bytes',data.encode(),hashlib.sha256).digest())
def auth():
 data=[{'userId':10000+i,'index':i,'token':jwt(i)} for i in range(1,1001)];path=ROOT/'auth.local.json';path.write_text(json.dumps(data));path.chmod(0o600)
 commands=[]
 for i in range(1,1001):commands.extend([['HSET',f'yeodam:polltest:auth:session:poll-session-{i}','userId',str(10000+i),'refreshTokenHash','test-hash'],['EXPIRE',f'yeodam:polltest:auth:session:poll-session-{i}','7200']])
 raw=b''
 for c in commands:
  raw+=f'*{len(c)}\r\n'.encode()
  for s in c:
   b=s.encode();raw+=f'${len(b)}\r\n'.encode()+b+b'\r\n'
 subprocess.run(DC+['exec','-T','redis','redis-cli','--pipe'],input=raw,capture_output=True,check=True)
 return data

def state(phase):
 req=urllib.request.Request('http://127.0.0.1:19999/control',data=json.dumps({'phase':phase}).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)

def reset(kind):
 sql("UPDATE story_generation_jobs SET status='COMPLETED',story_id=generation_id,progress=100;UPDATE trips SET processing_status='COMPLETED';UPDATE trip_creation_executions SET status='COMPLETED';UPDATE additional_attachment_executions SET status='COMPLETED',ended_at=UTC_TIMESTAMP(6);")
 if kind=='story':sql("UPDATE story_generation_jobs SET status='QUEUED',story_id=NULL,progress=0;")
 elif kind=='creation':sql("UPDATE trips SET processing_status='PROCESSING' WHERE trip_id BETWEEN 20001 AND 21000;UPDATE trip_creation_executions SET status='AI_QUEUED',input_closed=TRUE,process_published_at=UTC_TIMESTAMP(6);")
 else:sql("UPDATE additional_attachment_executions SET status='AI_QUEUED',ended_at=NULL,input_closed=TRUE,process_published_at=UTC_TIMESTAMP(6),last_upload_activity_at=UTC_TIMESTAMP(6);")
 state('QUEUED')

def transition(kind,phase):
 q=None
 if kind=='story':q="UPDATE story_generation_jobs SET status='PROCESSING',progress=50;" if phase=='PROCESSING' else "UPDATE story_generation_jobs SET status='COMPLETED',progress=100,story_id=generation_id;"
 elif phase=='COMPLETED':
  q="UPDATE trips SET processing_status='COMPLETED' WHERE trip_id BETWEEN 20001 AND 21000;UPDATE trip_creation_executions SET status='COMPLETED';" if kind=='creation' else "UPDATE additional_attachment_executions SET status='COMPLETED',ended_at=UTC_TIMESTAMP(6);"
 if q:
  times=[float(v) for v in sql('SELECT UNIX_TIMESTAMP(NOW(6))*1000;START TRANSACTION;'+q+'COMMIT;SELECT UNIX_TIMESTAMP(NOW(6))*1000;').strip().splitlines()]
  left,right=times[0]-clock_offset(),times[-1]-clock_offset()
  state(phase)
 else:
  ack=state(phase);left=right=ack['epochMs']-clock_offset()
 return left,right

def clock_offset():
 return CLOCK_OFFSET
CLOCK_OFFSET=0

def seed_fixture():
 count=sql("SELECT (SELECT COUNT(*) FROM users WHERE NOT (user_id=1 AND email='system@yeodam.invalid')) + (SELECT COUNT(*) FROM trips);")
 if int(count.strip()) != 0:raise SystemExit('Fixture requires a fresh database with only the migration system user; refusing to insert again.')
 sql(generate());auth();print('fixture ready')

def once(kind,n,rep,warm=30,duration=120):
 global CLOCK_OFFSET
 clocks=[]
 for _ in range(5):
  a=time.time()*1000;b=get('http://127.0.0.1:19999/clock')['epochMs'];c=time.time()*1000;clocks.append((c-a,b-(a+c)/2))
 CLOCK_OFFSET=min(clocks)[1]
 auth();reset(kind);label=f'{kind}-n{n}-r{rep}';d=ROOT/'runs'/label;d.mkdir(parents=True,exist_ok=True)
 start=time.time()+3;finish=start+warm+duration
 env=dict(os.environ,AUTH_FILE=str(ROOT/'auth.local.json'),START_MS=str(start*1000),WARM_SECONDS=str(warm),MEASURE_SECONDS=str(duration),KIND=kind,VUS=str(n),RUN_ID=label,SUMMARY_FILE=str(d/'k6-summary.json'))
 before={'db':statdb(),'ai':get('http://127.0.0.1:19999/stats')};markers=[]
 with (d/'stdout.log').open('w') as out:
  probe_out=(d/'probe-stdout.log').open('w')
  probe_env=dict(env,PROBE_ONLY='1',SUMMARY_FILE=str(d/'probe-summary.json'))
  probe=subprocess.Popen(['k6','run','--quiet','--log-format','raw','--log-output','file='+str(d/'probe-events.jsonl'),str(ROOT/'polling.js')],env=probe_env,stdout=probe_out,stderr=subprocess.STDOUT)
  proc=subprocess.Popen(['k6','run','--quiet','--log-format','raw','--log-output','file='+str(d/'events.jsonl'),str(ROOT/'polling.js')],env=env,stdout=out,stderr=subprocess.STDOUT)
  for offset,phase in [(warm+duration*0.25,'PROCESSING'),(warm+duration*0.875,'COMPLETED')]:
   time.sleep(max(0,start+offset-time.time()));left,right=transition(kind,phase)
   markers.append({'phase':phase,'earliestCommitMs':left,'latestCommitMs':right});(d/'markers.json').write_text(json.dumps(markers,indent=2))
  code=proc.wait(timeout=40)
  probe_code=probe.wait(timeout=10);probe_out.close()
  if probe_code:raise RuntimeError('probe failed '+label)
 after={'db':statdb(),'ai':get('http://127.0.0.1:19999/stats')}
 (d/'metadata.json').write_text(json.dumps({'kind':kind,'vus':n,'repeat':rep,'start':start,'measurement_start':start+warm,'measurement_end':finish,'warmup_seconds':warm,'measurement_seconds':duration,'exit_code':code,'before':before,'after':after,'clock_offset_ms':CLOCK_OFFSET,'clock_calibration_min_rtt_ms':min(clocks)[0]},indent=2))
 queries=json.loads((ROOT/'prom_queries.json').read_text())
 output={k:prom('query_range',{'query':q,'start':start+warm,'end':finish,'step':'5s'}) for k,q in queries.items()}
 (d/'prometheus.json').write_text(json.dumps(output))
 summary=json.loads((d/'k6-summary.json').read_text()); metrics=summary['metrics'];metrics.update(json.loads((d/'probe-summary.json').read_text())['metrics']);events=[]
 for line in (d/'events.jsonl').read_text().splitlines():
  try:
   e=json.loads(line)
   if e.get('event')=='observed':events.append(e)
  except (ValueError,AttributeError):pass
 latency={};expected='PROCESSING' if kind=='story' else 'CLUSTERING'
 for m in markers:
  seen={}
  for e in events:
   matches=e['status']=='COMPLETED' if m['phase']=='COMPLETED' else (e['status']=='PROCESSING' if kind=='story' else e['step']=='CLUSTERING')
   if matches and e['userId'] not in seen:seen[e['userId']]=e['epochMs']-m['latestCommitMs']
  a=sorted(max(0,x) for x in seen.values());latency[m['phase']]={'received_users':len(a),'p50_ms':statistics.median(a) if a else None,'p95_ms':a[min(len(a)-1,int(len(a)*.95))] if a else None,'p99_ms':a[min(len(a)-1,int(len(a)*.99))] if a else None,'max_ms':max(a) if a else None,'commit_bracket_ms':m['latestCommitMs']-m['earliestCommitMs']}
 result={'label':label,'kind':kind,'vus':n,'repeat':rep,'measurement_seconds':duration,'exit_code':code,'poll_requests':metrics.get('poll_requests',{}).get('values',{}).get('count',0),'poll_latency_ms':metrics.get('poll_latency_ms',{}).get('values',{}),'poll_errors':metrics.get('poll_errors',{}).get('values',{}),'map_probe_latency_ms':metrics.get('map_probe_latency_ms',{}).get('values',{}),'map_probe_errors':metrics.get('map_probe_errors',{}).get('values',{}),'progress_field_absent':metrics.get('progress_field_absent',{}).get('values',{}),'status_delivery':latency,'mysql_select_delta':after['db']['Com_select']-before['db']['Com_select'],'ai_request_delta':after['ai']['requests']-before['ai']['requests']}
 (d/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps({'completed':label,'exit_code':code,'p95_ms':result['poll_latency_ms'].get('p(95)'),'received':latency.get('COMPLETED',{}).get('received_users')}),flush=True)
 if code or result['map_probe_errors'].get('rate',1)>0 or any(x['received_users']!=n for x in latency.values()):raise RuntimeError('Invalid run '+label)
 return result
if __name__=='__main__':
 if len(sys.argv)>1 and sys.argv[1]=='fixture':
  seed_fixture()
 elif len(sys.argv)>1 and sys.argv[1]=='smoke':
  for kind in ['story','creation','update']:once(kind,2,0,2,12)
 else:
  results=[]
  for rep in [1]:
   for n in ([100,500,1000] if rep!=2 else [1000,500,100]):
    for kind in ['story','creation','update']:
     path=ROOT/'runs'/f'{kind}-n{n}-r{rep}'/'result.json'
     if path.exists():
      item=json.loads(path.read_text())
      if item['exit_code'] or any(v['received_users']!=n for v in item['status_delivery'].values()):raise RuntimeError('Existing invalid run')
      print('REUSE_VALID '+item['label'],flush=True)
     else:item=once(kind,n,rep)
     results.append(item);(ROOT/'summary.json').write_text(json.dumps(results,indent=2))
  print('ALL_RUNS_COMPLETED',flush=True)
