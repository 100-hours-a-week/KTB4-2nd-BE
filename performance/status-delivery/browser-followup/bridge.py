import json, sys, time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
import run

run.auth()
clocks = []
for _ in range(5):
    a = time.time()*1000
    b = run.get('http://127.0.0.1:19999/clock')['epochMs']
    c = time.time()*1000
    clocks.append((c-a, b-(a+c)/2))
run.CLOCK_OFFSET = min(clocks)[1]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/reset':
            run.reset('story')
            result = {'db': run.statdb(), 'clock_offset_ms': run.CLOCK_OFFSET}
        elif self.path == '/phase':
            left, right = run.transition('story', body['phase'])
            result = {'phase': body['phase'], 'earliestCommitMs': left, 'latestCommitMs': right}
        elif self.path == '/stats':
            result = {'db': run.statdb()}
        else:
            self.send_error(404); return
        data = json.dumps(result).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)

print('BRIDGE_READY', flush=True)
ThreadingHTTPServer(('127.0.0.1', 19099), Handler).serve_forever()
