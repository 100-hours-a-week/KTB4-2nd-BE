import json
from pathlib import Path
import sys
import tempfile
import unittest
import quick
import run


class QuickTest(unittest.TestCase):
    def test_plan_uses_existing_phase_models_without_large_repetitions(self):
        entries = [dict(label=name, path=__file__, mime_type=mime, width=1, height=1)
                   for name, mime in [('jpg-low','image/jpeg'), ('jpg-high','image/jpeg'),
                   ('png-high','image/png'), ('heic-low','image/heic'), ('heic-high','image/heic'), ('png-low','image/png')]]
        plans = quick.build_plan(entries)
        self.assertEqual(7, len(plans))
        with tempfile.TemporaryDirectory() as folder:
            counts = []
            for label, config in plans:
                run.validate_config(config)
                _, phases, count = run.plan_phases(config, Path(folder)/label)
                counts.append(count)
            self.assertEqual([2,2,2,4,3,3,3], counts)
        self.assertEqual([.5,1], plans[4][1]['small_start_seconds'])

    def test_deadline_stops_child_and_skips_following_conditions(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            script = output/'child.py'
            script.write_text('import signal,time\nfrom pathlib import Path\n'
                              'signal.signal(signal.SIGTERM, lambda *_: (Path(__file__).with_suffix(".stopped").touch(), exit(1)))\n'
                              'time.sleep(10)\n')
            status = quick.execute([('one',{}),('two',{})], output,
                load_seconds=.3, cleanup_seconds=1, minimum_seconds=0,
                command=[sys.executable,str(script)])
            self.assertFalse(status['complete'])
            self.assertEqual('interrupted', status['conditions'][0]['status'])
            self.assertEqual('skipped_budget', status['conditions'][1]['status'])
            self.assertTrue(script.with_suffix('.stopped').exists())
            self.assertEqual(status, json.loads((output/'status.json').read_text()))

    def test_success_and_insufficient_budget(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            marker = output/'started'
            command = [sys.executable,'-c',f'from pathlib import Path; Path({str(marker)!r}).touch()']
            status = quick.execute([('one',{})], output, load_seconds=1,
                                   cleanup_seconds=1, minimum_seconds=2, command=command)
            self.assertFalse(marker.exists())
            self.assertEqual('skipped_budget', status['conditions'][0]['status'])
            status = quick.execute([('one',{})], output, load_seconds=1,
                                   cleanup_seconds=1, minimum_seconds=0, command=command)
            self.assertTrue(marker.exists())
            self.assertTrue(status['complete'])

    def test_failure_prevents_following_load(self):
        with tempfile.TemporaryDirectory() as folder:
            status = quick.execute([('one',{}),('two',{})], Path(folder),
                load_seconds=2, cleanup_seconds=1, minimum_seconds=0,
                command=[sys.executable,'-c','raise SystemExit(1)'])
            self.assertEqual(['failed','skipped_previous_failure'], [r['status'] for r in status['conditions']])

class NewPlanTest(unittest.TestCase):
    def entries(self):
        return [dict(label=f'{fmt}-{size}',path=__file__,mime_type=mime,width=1,height=1)
            for fmt,mime in [('jpg','image/jpeg'),('png','image/png'),('heic','image/heic')] for size in ('low','high')]

    def test_new_plan_maps_scenarios_and_keeps_delay_controls_separate(self):
        for scenario,model,count in [('S0','S0',3),('S1','S1',6),('S2','S2',3),('S4','S5',2),('S5','S6',3),('S6','S3',2),('S7','S7',2)]:
            plans=quick.build_plan(self.entries(),'2026-10-02',scenario)
            self.assertEqual(count,len(plans),scenario)
            self.assertEqual(model,plans[-1][1]['scenario'])
            for _,config in plans:
                self.assertEqual(scenario,config['scenario_id']); run.validate_config(config)
        plans=quick.build_plan(self.entries(),'2026-10-02','S5')
        self.assertFalse(plans[0][1].get('fault'))
        self.assertEqual(['original_read','put_preview'],[c['fault']['stage'] for _,c in plans[1:]])

    def test_compare_keeps_observed_bottleneck_rate_and_verify_runs_have_individual_budgets(self):
        base=quick.build_plan(self.entries(),'2026-10-02','S5')[1][1]
        base.update(rate=7,time_unit_seconds=60,n=5)
        manifest=dict(run_id='baseline',config=base,photos=base['files'])
        compare=quick.build_plan(self.entries(),'2026-10-02','S5','compare',manifest)
        self.assertEqual(1,len(compare));self.assertEqual(7,compare[0][1]['rate'])
        self.assertEqual(60,compare[0][1]['time_unit_seconds']);self.assertEqual(90,compare[0][1]['measurement_seconds'])
        verify=quick.build_plan(self.entries(),'2026-10-02','S5','verify',manifest)
        self.assertEqual(3,len(verify));self.assertEqual([600]*3,[c['total_budget_seconds'] for _,c in verify])
        with self.assertRaises(ValueError): quick.build_plan(self.entries(),'2026-10-02','S3','compare')

    def test_fixed_duration_warmup_and_measurement_have_disjoint_fixtures(self):
        config=quick.build_plan(self.entries(),'2026-10-02','S2')[1][1]
        with tempfile.TemporaryDirectory() as folder:
            _,phases,count=run.plan_phases(config,Path(folder))
        self.assertEqual(['constant-vus','constant-vus'],[p['executor'] for p in phases])
        self.assertEqual([30,120],[p['duration'] for p in phases])
        self.assertEqual(phases[0]['count'],phases[1]['offset'])
        self.assertEqual(sum(p['count'] for p in phases),count)

    def test_probe_uses_mu0_instead_of_inventing_s3_capacity(self):
        base=quick.build_plan(self.entries(),'2026-10-02','S2')[1][1]
        manifest=dict(run_id='s2',config=base,photos=base['files'],results=dict(worker_execution_seconds=10,committed_photos=20))
        plans=quick.build_plan(self.entries(),'2026-10-02','S3','probe',manifest)
        self.assertEqual([12,19,29],[p['rate'] for p in plans[0][1]['arrival_steps']])
        with self.assertRaises(ValueError): quick.build_plan(self.entries(),'2026-10-02','S3')

class SavedBaselineTest(unittest.TestCase):
    def entries(self):
        return [dict(label=f'{fmt}-{size}',path=__file__,mime_type=mime,width=1,height=1,sha256='hash-'+fmt+size)
            for fmt,mime in [('jpg','image/jpeg'),('png','image/png'),('heic','image/heic')] for size in ('low','high')]
    def manifest(self,config):
        return dict(run_id='saved',config={k:v for k,v in config.items() if k not in ('files','small_files')},
            photos=[{k:v for k,v in e.items() if k!='path'} for e in config['files']],
            small_photos=[{k:v for k,v in e.items() if k!='path'} for e in config.get('small_files',[])])
    def test_mixed_s4_restores_small_input_from_saved_manifest(self):
        config=quick.build_plan(self.entries(),'2026-10-02','S4')[-1][1]
        compare=quick.build_plan(self.entries(),'2026-10-02','S4','compare',self.manifest(config))[0][1]
        self.assertEqual('jpg-low',compare['small_files'][0]['label'])
        run.validate_config(compare,check_files=False)
    def test_selected_s3_phase_is_compared_to_effective_baseline_rate(self):
        base=quick.build_plan(self.entries(),'2026-10-02','S5')[0][1];base.update(scenario_id='S3',scenario='S3')
        manifest=self.manifest(base);manifest['config']['arrival_steps']=[dict(rate=12,time_unit_seconds=60,duration=40)]
        manifest['bottleneck_rate']=dict(rate=12,time_unit_seconds=60)
        compare=quick.build_plan(self.entries(),'2026-10-02','S3','compare',manifest)[0][1]
        self.assertTrue(run.compare_runs(dict(manifest,config=compare),manifest)['valid'])
    def test_closed_model_is_never_claimed_comparable_to_default_arrival_rate(self):
        config=quick.build_plan(self.entries(),'2026-10-02','S1')[0][1]
        with self.assertRaisesRegex(ValueError,'fixed-arrival baseline'):
            quick.build_plan(self.entries(),'2026-10-02','S1','compare',self.manifest(config))
    def test_s7_verify_preserves_recovery_window_with_bounded_fault_phase(self):
        config=quick.build_plan(self.entries(),'2026-10-02','S7')[0][1]
        plans=quick.build_plan(self.entries(),'2026-10-02','S7','verify',self.manifest(config))
        for _,config in plans:
            run.validate_config(config,check_files=False)
            phases=run.versioned_phases(config)
            self.assertEqual(60,phases[1]['max_duration_seconds']);self.assertEqual(180,phases[2]['max_duration_seconds'])

class IndependentBudgetTest(unittest.TestCase):
    def test_each_repetition_gets_full_deadline(self):
        from unittest.mock import patch
        clock=[0]; waits=[]
        class Child:
            returncode=0
            def wait(self,timeout): waits.append(timeout);clock[0]+=300;return 0
        with tempfile.TemporaryDirectory() as folder, patch.object(quick.time,'monotonic',side_effect=lambda:clock[0]),patch.object(quick.subprocess,'Popen',side_effect=lambda *a,**k:Child()):
            status=quick.execute([('one',dict(total_budget_seconds=600)),('two',dict(total_budget_seconds=600))],Path(folder),independent=True)
            self.assertTrue(status['complete']);self.assertEqual([600,600],waits)

    def test_cleanup_deadline_blocks_following_repetition(self):
        from unittest.mock import patch
        clock=[0]
        class Child:
            pid=123
            def wait(self,timeout):clock[0]+=timeout;raise quick.subprocess.TimeoutExpired('child',timeout)
            def terminate(self):pass
        with tempfile.TemporaryDirectory() as folder,patch.object(quick.time,'monotonic',side_effect=lambda:clock[0]),patch.object(quick.subprocess,'Popen',return_value=Child()):
            status=quick.execute([('one',{}),('two',{})],Path(folder),independent=True)
            self.assertFalse(status['complete']);self.assertEqual('cleanup_pending',status['conditions'][0]['status'])
            self.assertEqual('skipped_budget',status['conditions'][1]['status'])

if __name__ == '__main__':
    unittest.main()
