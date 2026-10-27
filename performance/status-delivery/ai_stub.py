import json,time,re,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
counts={"requests":0}; lock=threading.Lock(); current={"phase":"QUEUED"}
class Handler(BaseHTTPRequestHandler):
 protocol_version="HTTP/1.1"
 def log_message(self,*args):pass
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))));phase=body['phase']
  if self.path!='/control' or phase not in ['QUEUED','PROCESSING','COMPLETED']:self.send_error(400);return
  with lock:current['phase']=phase;stamp=time.time()*1000
  b=json.dumps({'phase':phase,'epochMs':stamp}).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
 def do_GET(self):
  if self.path=="/clock": body={"epochMs":time.time()*1000}
  elif self.path=="/stats": body=dict(counts)
  else:
   m=re.fullmatch(r"/trips/(\d+)/process",self.path)
   if not m: body={"status":"ok"}
   else:
    with lock: counts["requests"]+=1
    trip=int(m.group(1)); kind="creation" if 20001<=trip<=21000 else "update"; i=trip-(20000 if kind=="creation" else 30000)
    with lock: phase=current["phase"]
    body={"trip_id":trip,"execution_id":f"poll-{kind}-{i}","status":"PROCESSING","progress":{"done":0,"total":10},"current_step":"EMBEDDING" if phase=="QUEUED" else "CLUSTERING","result":None,"error":None}
  b=json.dumps(body).encode();self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(b)));self.end_headers();self.wfile.write(b)
ThreadingHTTPServer(("0.0.0.0",9999),Handler).serve_forever()
