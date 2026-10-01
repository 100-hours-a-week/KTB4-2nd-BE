import { check, sleep } from 'k6';
import http from 'k6/http';

export const options = {
  vus: 1,
  duration: '10s',
  tags: { testid: 'prometheus-smoke' },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0.5'],
  },
};

export default function () {
  const baseUrl = __ENV.PROMETHEUS_URL || 'http://localhost:9090';
  check(http.get(`${baseUrl}/-/ready`), { ready: (r) => r.status === 200 });
  check(http.get(`${baseUrl}/k6-smoke-not-found`), { 'expected 404': (r) => r.status === 404 });
  sleep(1);
}
