import unittest
from unittest.mock import Mock
import subprocess
import tempfile
from pathlib import Path
import json
import run
import compare

class AuthReportTest(unittest.TestCase):
    def test_observer_is_killed_if_termination_times_out(self):
        process = Mock()
        process.wait.side_effect = [subprocess.TimeoutExpired("docker stats", 5), 0]
        output, worker = Mock(), Mock()
        run.stop_container_observer((process, output, worker))
        process.terminate.assert_called_once()
        process.kill.assert_called_once()
        self.assertEqual(process.wait.call_count, 2)
        output.close.assert_called_once()

    def test_failed_and_drain_requests_do_not_inflate_success_rate(self):
        rows = [dict(phase='measurement', epoch_ms=1500, status=200, duration_ms=10),
                dict(phase='measurement', epoch_ms=1600, status=500, duration_ms=1),
                dict(phase='measurement', epoch_ms=2500, status=200, duration_ms=30),
                dict(phase='warmup', epoch_ms=1400, status=200, duration_ms=2)]
        report = run.summarize(rows, 1000, 2000)
        self.assertEqual(report['success_rps'], 1)
        self.assertEqual(report['measurement_requests'], 3)
        self.assertEqual(report['drain_completions'], 1)
        self.assertEqual(report['failed_requests'], 1)
        self.assertEqual(report['success_p95_ms'], 30)

    def test_cumulative_cpu_is_scoped_to_the_measurement_window(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'resources.jsonl'
            path.write_text('\n'.join(json.dumps(row) for row in [
                dict(epoch_ms=0, server_cpu_seconds=5, mysql_cpu_seconds=20, server_rss_bytes=10),
                dict(epoch_ms=1000, server_cpu_seconds=7, mysql_cpu_seconds=22, server_rss_bytes=20),
                dict(epoch_ms=2000, server_cpu_seconds=11, mysql_cpu_seconds=26, server_rss_bytes=30)]))
            report = run.resource_report(path, 500, 1500, 10)
            self.assertEqual(report['server_cpu_seconds'], 3)
            self.assertEqual(report['server_cpu_seconds_per_success'], .3)
            self.assertEqual(report['mysql_cpu_seconds'], 3)
            self.assertEqual(report['server_rss_bytes_peak'], 20)
        self.assertEqual(run.cpu_seconds('01:02.5'), 62.5)
        self.assertEqual(run.cpu_seconds('1-01:00:00'), 90000)

    def test_container_backend_memory_is_not_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'resources.jsonl'
            path.write_text('\n'.join(json.dumps(row) for row in [
                dict(epoch_ms=0,server_cpu_seconds=0,mysql_cpu_seconds=0,server_memory_bytes=100),
                dict(epoch_ms=1000,server_cpu_seconds=1,mysql_cpu_seconds=1,server_memory_bytes=200)]))
            report=run.resource_report(path,0,1000,10)
            self.assertEqual(report.get('server_memory_bytes_peak'),200)

    def test_k6_timestamp_with_variable_fractional_precision(self):
        self.assertAlmostEqual(run.k6_epoch_ms('2026-10-03T15:49:22.14141+09:00'),
                               run.k6_epoch_ms('2026-10-03T06:49:22.141410000Z'), places=3)

    def test_raw_client_completion_defines_success_rps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); case = root / 'case'; case.mkdir()
            events = [dict(type='Point', metric='auth_window_start_ms', data=dict(value=1000)),
                dict(type='Point', metric='http_req_duration', data=dict(value=5,
                     time='1970-01-01T00:00:01.500Z', tags=dict(phase='measurement', status='200', fixture_index='0'))),
                dict(type='Point', metric='http_req_duration', data=dict(value=50,
                     time='1970-01-01T00:00:02.100Z', tags=dict(phase='measurement', status='200', fixture_index='1')))]
            (case / 'k6-raw.json').write_text('\n'.join(json.dumps(e) for e in events))
            (root / 'server-requests.csv').write_text(
                'epoch_ms,phase,scenario,status,duration_ms,stage,calls,stage_ms,sql_count,sql_ms\n'
                '1490,measurement,S1,200,4,http,1,0,0,0\n'
                '1990,measurement,S1,200,40,http,1,0,0,0\n')
            report = run.parse_report(case, 0, 1)
            self.assertEqual(report['success_rps'], 1)
            self.assertEqual(report['server_success_rps'], 2)
            self.assertEqual(report['client_drain_completions'], 1)
            self.assertEqual(report['actual_accessed_inputs'], 2)

    def test_compare_refuses_different_input_or_invalid_results(self):
        a = dict(manifest=dict(dataset='one'), reports=[])
        b = dict(manifest=dict(dataset='two'), reports=[])
        with self.assertRaisesRegex(ValueError, 'dataset'):
            compare.compare(a, b)
        row = dict(valid=False)
        a['reports'] = [row]; b['reports'] = [row]; b['manifest'] = a['manifest']
        with self.assertRaisesRegex(ValueError, 'Invalid load'):
            compare.compare(a, b)

    def test_remote_or_non_dedicated_database_is_rejected(self):
        for url in ['jdbc:mysql://remote:3306/auth_performance', 'jdbc:mysql://127.0.0.1:13307/yeodam']:
            with self.assertRaises(ValueError):
                run.validate_mysql(url)
        run.validate_mysql('jdbc:mysql://127.0.0.1:13307/auth_performance')

    def test_write_pairs_use_half_the_http_arrival_rate(self):
        self.assertEqual(run.iteration_rate('S3', 100), 50)
        with self.assertRaises(ValueError):
            run.iteration_rate('S3', 25)
        self.assertEqual(run.iteration_rate('S1', 25), 25)

if __name__ == '__main__':
    unittest.main()
