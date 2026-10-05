import unittest
import tempfile
import json
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('capacity', Path(__file__).with_name('capacity.py'))
capacity = importlib.util.module_from_spec(spec) if spec and Path(spec.origin).exists() else None
if capacity:
    spec.loader.exec_module(capacity)

class CapacityTest(unittest.TestCase):
    def require_tool(self):
        self.assertIsNotNone(capacity, 'capacity runner is not implemented')
    def sample(self):
        return dict(requested_http_rps=100, success_rps=100, client_failed_requests=0,
                    failed_checks=0, dropped_iterations=0, client_success_p95_ms=20,
                    client_measurement_requests=1000, measurement_requests=1000,
                    endpoints={'place': 500, 'me': 200, 'favorite-add': 100,
                               'favorite-remove': 100, 'refresh': 100}, refresh_p95_ms=30,
                    resources_complete=True, lifecycle_complete=True)
    def test_successful_but_slow_refresh_is_not_sustainable(self):
        self.require_tool()
        report = self.sample(); report['refresh_p95_ms'] = 201
        self.assertFalse(capacity.assess(report, 200)['sustainable'])

    def test_pending_warmup_cannot_be_capacity_evidence(self):
        report=self.sample(); report['stabilization']={'required':True,'passed':False}
        self.assertFalse(capacity.assess(report,200)['valid'])

    def test_database_profile_is_assessed_using_its_own_mix(self):
        report=self.sample(); report['profile']='db'
        report['endpoints']={'place':200,'me':200,'trips':300,'favorite-add':100,'favorite-remove':100,'refresh':100}
        self.assertTrue(capacity.assess(report,200)['sustainable'])

    def test_burst_mix_uses_foreground_not_added_refresh(self):
        report=self.sample();report['profile']='rtr-burst'
        report['foreground_endpoints']=dict(report['endpoints'])
        report['endpoints']['refresh']+=200
        self.assertTrue(capacity.assess(report,200)['valid'])
        self.assertFalse(capacity.assess(dict(report,foreground_endpoints=report['endpoints']),200)['valid'])

    def test_environment_clock_gap_invalidates_measurement(self):
        report=self.sample(); report['environment_complete']=False
        self.assertFalse(capacity.assess(report,200)['valid'])
    def test_drop_after_stabilization_is_overload_not_environment_failure(self):
        report=self.sample();report['dropped_iterations']=3
        result=capacity.assess(report,200)
        self.assertTrue(result['valid'])
        self.assertFalse(result['sustainable'])

    def test_missing_load_or_wrong_mix_cannot_claim_capacity(self):
        self.require_tool()
        for change in [dict(success_rps=98), dict(dropped_iterations=1),
                       dict(measurement_requests=999), dict(resources_complete=False),
                       dict(endpoints={'place': 1000}), dict(failed_checks=1)]:
            report = self.sample(); report.update(change)
            self.assertFalse(capacity.assess(report, 200)['sustainable'])
        self.assertTrue(capacity.assess(self.sample(), 200)['sustainable'])
    def test_search_brackets_and_confirms_instead_of_declaring_ceiling(self):
        self.require_tool()
        visited = []
        def probe(rate, duration):
            visited.append((rate, duration)); return dict(sustainable=rate <= 350, valid=True)
        result = capacity.search(probe, start=100, ceiling=800, precision=100, step_seconds=60)
        self.assertEqual(result['confirmed_rps'], 300)
        self.assertEqual(result['failed_upper_rps'], 400)
        self.assertEqual(visited[-2:], [(300, 180), (300, 180)])
    def test_all_passed_ceiling_is_a_lower_bound(self):
        self.require_tool()
        result = capacity.search(lambda r,d: dict(sustainable=True, valid=True),100,400,100,60)
        self.assertTrue(result['censored']); self.assertEqual(result['confirmed_rps'],400)
    def test_incomplete_probe_aborts_search_without_capacity_claim(self):
        self.require_tool()
        with self.assertRaisesRegex(RuntimeError,'Invalid'):
            capacity.search(lambda r,d: dict(sustainable=False,valid=False),100,400,100,60)
    def test_confirmation_without_full_user_coverage_cannot_claim_capacity(self):
        self.require_tool()
        result=capacity.search(lambda r,d: dict(sustainable=True,valid=True,coverage_complete=False),100,400,100,60)
        self.assertIsNone(result['confirmed_rps'])
        self.assertTrue(result['confirmation_failed'])

    def test_comparison_requires_same_total_budget_and_mix(self):
        self.require_tool()
        a=dict(total_cpu=4,total_memory=4294967296,active_users=10000,mix=[5,2,1,1,1],p95_limit_ms=200)
        b=dict(a); b['active_users']=1000
        with self.assertRaises(ValueError):capacity.validate_comparison(a,b)
        capacity.validate_comparison(a,dict(a))
        b=dict(a,warmup_policy={'version':2})
        with self.assertRaises(ValueError):capacity.validate_comparison(a,b)


class MixedRawTest(unittest.TestCase):
    def test_numeric_coverage_does_not_need_high_cardinality_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            case=Path(directory)
            events=[dict(type='Point',metric='auth_window_start_ms',data=dict(value=1000))]
            for index,name in [(7,'place'),(8008,'refresh')]:
                tags=dict(phase='measurement',status='200',name=name,scenario='mixed')
                events.append(dict(type='Point',metric='auth_fixture_index',data=dict(value=index,tags=tags)))
                events.append(dict(type='Point',metric='http_req_duration',data=dict(value=30,time='1970-01-01T00:00:01.500Z',tags=tags)))
            (case/'k6-raw.json').write_text('\n'.join(json.dumps(e) for e in events))
            (case/'server-requests.csv').write_text('epoch_ms,phase,scenario,status,duration_ms,stage,calls,stage_ms,sql_count,sql_ms\n1500,measurement,MIX,200,4,http,1,0,0,0\n1500,measurement,MIX,200,29,http,1,0,0,0\n')
            report=capacity.parse_mixed(case,1)
            self.assertEqual(report['actual_accessed_inputs'],2)
            self.assertEqual(report['actual_refresh_inputs'],1)
            self.assertEqual(report['endpoint_metrics']['refresh']['p95_ms'],30)

    def test_refresh_and_checks_are_measured_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            case=Path(directory)
            events=[dict(type='Point',metric='auth_window_start_ms',data=dict(value=1000))]
            for name,value in [('place',5),('refresh',30)]:
                events.append(dict(type='Point',metric='http_req_duration',data=dict(value=value,time='1970-01-01T00:00:01.500Z',tags=dict(phase='measurement',status='200',name=name,fixture_index='0'))))
            events.append(dict(type='Point',metric='checks',data=dict(value=0,tags=dict(phase='measurement'))))
            (case/'k6-raw.json').write_text('\n'.join(json.dumps(e) for e in events))
            (case/'server-requests.csv').write_text('epoch_ms,phase,scenario,status,duration_ms,stage,calls,stage_ms,sql_count,sql_ms\n1500,measurement,MIX,200,4,http,1,0,0,0\n1500,measurement,MIX,200,29,http,1,0,0,0\n')
            report=capacity.parse_mixed(case,1)
            self.assertEqual(report['refresh_p95_ms'],30)
            self.assertEqual(report['failed_checks'],1)
            self.assertEqual(report['endpoints'],{'place':1,'refresh':1})
            self.assertEqual(report['measurement_requests'],2)

class StoreVariantsTest(unittest.TestCase):
    def test_b2_requires_redis_csrf_and_b1_requires_rdb_csrf(self):
        for variant in ('A', 'B', 'B1', 'B2'):
            session, csrf = capacity.STORES[variant]
            capacity.validate_stores(variant, {'store': session, 'csrfStore': csrf})
        with self.assertRaises(ValueError):
            capacity.validate_stores('B2', {'store': 'redis', 'csrfStore': 'rdb'})
        with self.assertRaises(ValueError):
            capacity.validate_stores('B1', {'store': 'redis', 'csrfStore': 'redis'})
        with self.assertRaises(ValueError):
            capacity.validate_stores('B2', {'store': 'redis'})

    def test_runtime_csrf_evidence_cannot_be_missing_or_from_wrong_store(self):
        rdb = {'csrf': {'calls': 3, 'sql_count': 3}}
        redis = {'csrf': {'calls': 3, 'sql_count': 0}, 'csrf-redis-command': {'calls': 3}}
        self.assertTrue(capacity.csrf_evidence_matches('B1', rdb))
        self.assertTrue(capacity.csrf_evidence_matches('B2', redis))
        for stages in ({}, rdb, {'csrf': {'calls': 3, 'sql_count': 0}}):
            self.assertFalse(capacity.csrf_evidence_matches('B2', stages))
