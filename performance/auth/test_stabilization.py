import unittest
import json
import math
import tempfile
from pathlib import Path
try:
    import stabilization
except ImportError:
    stabilization=None


class StabilizationTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(stabilization,'stabilization is not implemented')
    def windows(self):
        return [dict(pending=0,failed=0,dropped=0,p95_ms=10+i,cpu_cores=.5+i*.02,jit_seconds=.5,complete=True,endpoint_p95={'remove':10+i},endpoint_counts={'remove':300}) for i in range(3)]

    def test_requires_three_good_windows_and_a_minute(self):
        self.assertFalse(stabilization.stable(self.windows()[:2],60))
        self.assertFalse(stabilization.stable(self.windows(),59))
        self.assertTrue(stabilization.stable(self.windows(),90))

    def test_rejects_pending_errors_and_cpu_or_jit_transition(self):
        for field,value in [('pending',178),('failed',1),('dropped',1),('jit_seconds',3.1),('cpu_cores',1.5),('p95_ms',200)]:
            windows=self.windows();windows[-1][field]=value
            self.assertFalse(stabilization.stable(windows,100),field)

    def test_prometheus_jit_is_cumulative_and_pending_is_not_summed_as_a_counter(self):
        metrics=stabilization.metrics('hikaricp_connections_pending{pool="p"} 178\njvm_compilation_time_ms_total{compiler="x"} 38310\n')
        self.assertEqual(metrics['pending'],178)
        self.assertEqual(metrics['jit_seconds'],38.31)

    def test_endpoint_transition_cannot_hide_in_stable_overall_p95(self):
        rows=self.windows()
        for row in rows:row['endpoint_p95']={'refresh':10,'place':10}
        rows[-1]['endpoint_p95']['refresh']=100
        self.assertFalse(stabilization.stable(rows,90))

    def test_missing_prometheus_metrics_are_incomplete(self):
        self.assertFalse(stabilization.metrics('process_cpu_usage 0.1\n')['complete'])


class WindowRegressionTest(unittest.TestCase):
    def rows(self, values):
        return [dict(pending=0,failed=0,dropped=0,p95_ms=7,cpu_cores=.23,jit_seconds=.5,
                     complete=True,endpoint_p95={'remove':v},endpoint_counts={'remove':300}) for v in values]

    def test_small_absolute_tail_variation_is_accepted_for_both_directions(self):
        for values in ([7.561,5.654,6.287],[5.654,6.287,7.561]):
            self.assertTrue(stabilization.stable(self.rows(values),90))

    def test_rejects_sparse_missing_nonfinite_and_growing_latency(self):
        for field,value in [('endpoint_counts',{'remove':249}),('complete',False),
                            ('endpoint_p95',{'remove':math.nan}),('endpoint_p95',{}),('jit_seconds',3.1)]:
            rows=self.rows([6,6,6]);rows[-1][field]=value
            self.assertFalse(stabilization.stable(rows,90),field)
        self.assertFalse(stabilization.stable(self.rows([6,9,12]),90))
        self.assertFalse(stabilization.stable(self.rows([6,6,6]),89))

    def test_event_time_window_ignores_file_order_and_boundary_future_events(self):
        def point(t,value,status='200'):
            return dict(type='Point',metric='http_req_duration',data=dict(time=t,value=value,
                        tags=dict(phase='warmup',name='remove',status=status)))
        events=[point('1970-01-01T00:00:30Z',999,'500'),point('1970-01-01T00:00:29Z',6),
                point('1970-01-01T00:00:01Z',4)]
        samples=[dict(epoch_ms=t,monotonic=t/1000,server_cpu_seconds=t/1000*.2,
                      application=dict(complete=True,pending=0,jit_seconds=t/1000*.01))
                 for t in range(0,36001,6000)]
        window=stabilization.window(events,samples,0,30000,{'remove'})
        self.assertEqual(window['endpoint_counts'],{'remove':2})
        self.assertEqual(window['failed'],0)
        self.assertAlmostEqual(window['cpu_cores'],.2)
        self.assertAlmostEqual(window['jit_seconds'],.3)
        self.assertEqual(window,stabilization.window(list(reversed(events)),samples,0,30000,{'remove'}))

    def test_missing_resources_do_not_produce_good_window(self):
        window=stabilization.window([],[],0,30000,{'remove'})
        self.assertFalse(window['complete'])
        self.assertIn('incomplete resources',stabilization.reasons([window]*3,90))


class RawReaderTest(unittest.TestCase):
    def test_partial_and_delayed_record_keeps_its_original_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'raw.json'
            first=json.dumps(dict(type='Point',metric='checks',data=dict(time='1970-01-01T00:00:01Z',value=1)))+'\n'
            delayed=json.dumps(dict(type='Point',metric='checks',data=dict(time='1970-01-01T00:00:02Z',value=0)))+'\n'
            path.write_text(first+delayed[:20])
            events,offset=stabilization.read_events(path)
            self.assertEqual(len(events),1)
            with path.open('a') as f:f.write(delayed[20:])
            more,end=stabilization.read_events(path,offset)
            self.assertEqual(len(more),1)
            self.assertEqual(more[0]['_epoch_ms'],2000)
            self.assertGreater(end,offset)

    def test_counter_reset_collection_error_and_gap_reject_window(self):
        base=[dict(epoch_ms=t,monotonic=t/1000,server_cpu_seconds=t/1000*.2,
                   application=dict(complete=True,pending=0,jit_seconds=t/1000*.01))
              for t in range(0,30001,6000)]
        for mutation in ['gap','error','reset']:
            rows=json.loads(json.dumps(base))
            if mutation=='gap':rows=rows[:1]+rows[4:]
            if mutation=='error':rows[2]['collection_error']='timeout'
            if mutation=='reset':rows[-1]['application']['jit_seconds']=-1
            w=stabilization.window([],rows,0,30000,{'remove'})
            # Fill latency/sample evidence to isolate resource rejection.
            w.update(p95_ms=6,endpoint_p95={'remove':6},endpoint_counts={'remove':300})
            self.assertFalse(stabilization.stable([w]*3,90),mutation)


class WatermarkTest(unittest.TestCase):
    def test_polling_clock_cannot_close_a_window_before_raw_events_arrive(self):
        self.assertEqual(stabilization.ready_window_count(95,1000,91000),2)
        self.assertEqual(stabilization.ready_window_count(95,1000,96000),3)
        self.assertEqual(stabilization.ready_window_count(89,1000,96000),2)


class LateFailureTest(unittest.TestCase):
    def test_failure_flushed_at_drain_invalidates_its_original_window(self):
        samples=[dict(epoch_ms=t,monotonic=t/1000,server_cpu_seconds=t/1000*.2,
                      application=dict(complete=True,pending=0,jit_seconds=t/1000*.01))
                 for t in range(0,30001,6000)]
        events=[dict(type='Point',metric='http_req_duration',data=dict(time='1970-01-01T00:00:01Z',value=6,
                    tags=dict(phase='warmup',name='remove',status='200'))) for _ in range(300)]
        good=stabilization.window(events,samples,0,30000,{'remove'})
        self.assertTrue(stabilization.stable([good]*3,90))
        events.append(dict(type='Point',metric='checks',data=dict(time='1970-01-01T00:00:02Z',value=0)))
        late=stabilization.window(events,samples,0,30000,{'remove'})
        self.assertFalse(stabilization.stable([good,good,late],90))
        self.assertIn('failed',stabilization.reasons([good,good,late],90))
