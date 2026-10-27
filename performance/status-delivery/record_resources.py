import subprocess,json,time,signal
from pathlib import Path
root=Path(__file__).resolve().parent
names=['yeodam-pollbase-'+x+'-1' for x in ['be1','be2','mysql','redis','ai','prometheus','redis-exporter']]
running=True
with (root/'docker-stats.jsonl').open('a') as f:
 while not (root/'STOP_STATS').exists():
  begin=time.time();p=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}']+names,text=True,capture_output=True)
  for line in p.stdout.splitlines():
   try:v=json.loads(line);v['epoch']=time.time();f.write(json.dumps(v)+'\n')
   except ValueError:pass
  f.flush();time.sleep(max(0,5-(time.time()-begin)))
