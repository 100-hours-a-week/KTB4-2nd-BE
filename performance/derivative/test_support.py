import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent

class CommandTest(unittest.TestCase):
    def execute(self, script, timeout=3):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, YEODAM_PERF_COMMAND_SAMPLES=directory,
                       YEODAM_PERF_COMMAND_TIMEOUT_SECONDS=str(timeout), YEODAM_PERF_COMMAND_GRACE_SECONDS='0.2')
            result = subprocess.run([sys.executable, str(HERE / 'command.py'), '--', sys.executable, '-c', script],
                                    env=env, capture_output=True, timeout=10)
            samples = list(Path(directory).glob('*.json'))
            self.assertEqual(1, len(samples), result.stderr)
            return result, json.loads(samples[0].read_text())

    def test_preserves_streams_and_collects_resources(self):
        result, sample = self.execute("import sys; a=bytearray(8*1024*1024); sum(range(1000000)); sys.stdout.buffer.write(b'[{}]'); sys.stderr.write('error')")
        self.assertEqual(0, result.returncode)
        self.assertEqual(b'[{}]', result.stdout)
        self.assertEqual(b'error', result.stderr)
        self.assertGreater(sample['max_rss_bytes'], 8*1024*1024)
        self.assertGreater(sample['user_cpu_ms'] + sample['system_cpu_ms'], 0)
        self.assertGreater(sample['wall_ms'], 0)
        self.assertFalse(sample['timeout'])

    def test_deadline_closes_stdout_and_kills_ignoring_child(self):
        result, sample = self.execute("import os,time,signal; signal.signal(signal.SIGTERM,signal.SIG_IGN); os.fork(); time.sleep(60)", 0.3)
        self.assertEqual(124, result.returncode)
        self.assertTrue(sample['timeout'])
        self.assertLess(sample['wall_ms'], 3000)
        self.assertFalse(sample['residual_pids'])

    def test_interrupt_keeps_resource_sample_and_reaps_child(self):
        import signal,time
        with tempfile.TemporaryDirectory() as directory:
            env=dict(os.environ,YEODAM_PERF_COMMAND_SAMPLES=directory,YEODAM_PERF_COMMAND_TIMEOUT_SECONDS='60',YEODAM_PERF_COMMAND_GRACE_SECONDS='0.1')
            process=subprocess.Popen([sys.executable,str(HERE/'command.py'),'--',sys.executable,'-c','import os,time; os.fork(); time.sleep(60)'],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            time.sleep(.2);process.send_signal(signal.SIGTERM)
            process.communicate(timeout=5)
            self.assertEqual(143,process.returncode)
            samples=list(Path(directory).glob('*.json'));self.assertEqual(1,len(samples))
            sample=json.loads(samples[0].read_text());self.assertEqual(signal.SIGTERM,sample['interrupted_signal'])
            self.assertFalse(sample['residual_pids'])

    @unittest.skipUnless(sys.platform == 'linux', 'actual Linux image binaries')
    def test_actual_images_preserve_bytes_through_wrapper(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            jpeg=root/'input.jpg';png=root/'input.png'
            for target in (jpeg,png):subprocess.run(['/usr/bin/convert','-size','32x24','gradient:red-blue',str(target)],check=True)
            heic=HERE.parent.parent/'src/test/resources/images/heic/oriented-with-exif.heic'
            env=dict(os.environ,YEODAM_PERF_COMMAND_SAMPLES=str(root/'samples'),YEODAM_PERF_COMMAND_TIMEOUT_SECONDS='10')
            for source in (jpeg,png,heic):
                outputs=[]
                for wrapped in (False,True):
                    target=root/(source.suffix[1:]+'-'+str(wrapped)+'.jpg')
                    args=['/usr/bin/convert',str(source)+'[0]','-resize','1024x1024>','-strip',str(target)]
                    if wrapped:args=[sys.executable,str(HERE/'command.py'),'--']+args
                    subprocess.run(args,env=env,check=True,capture_output=True,timeout=20)
                    outputs.append(hashlib.sha256(target.read_bytes()).hexdigest())
                self.assertEqual(outputs[0],outputs[1],source.name)

    @unittest.skipUnless(sys.platform == 'linux', 'Linux detached descendants')
    def test_detached_descendant_is_reaped(self):
        result, sample = self.execute("import os,time,signal; p=os.fork();\nif p==0: os.setsid(); signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)\nelse: time.sleep(60)", 0.3)
        self.assertEqual(124, result.returncode)
        self.assertFalse(sample['residual_pids'])
        self.assertGreaterEqual(sample['reaped_children'], 2)


class RunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('derivative_run', HERE / 'run.py')
        cls.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.runner)

    def config(self):
        return dict(scenario='S0', n=1, iterations=5, vus=1, bootstrap_seconds=60,
                    http_seconds=10, max_duration_seconds=60, graceful_seconds=10,
                    warmup_seconds=0, drain_seconds=30, total_budget_seconds=200,
                    token_ttl_seconds=3600, command_seconds=120, files=[])

    def test_rejects_invalid_budget_and_model(self):
        for key in ('bootstrap_seconds', 'http_seconds', 'max_duration_seconds', 'graceful_seconds',
                    'drain_seconds', 'total_budget_seconds'):
            config = self.config(); config.pop(key)
            with self.assertRaises(ValueError): self.runner.validate_config(config, check_files=False)
        for patch in ({'http_seconds': -1}, {'total_budget_seconds': 5},
                      {'token_ttl_seconds': 10}, {'scenario':'S3'}, {'n':11}):
            with self.assertRaises(ValueError): self.runner.validate_config(dict(self.config(), **patch), check_files=False)

    def test_multipart_repeats_files_in_order_and_splits_large_batches(self):
        from email import policy
        from email.parser import BytesParser
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = []
            for i, size in enumerate((15*1024*1024,)*10):
                path = root / ('image'+str(i)+'.jpg'); path.write_bytes(bytes([i])*size)
                files.append(dict(path=str(path), mime_type='image/jpeg', width=10,height=10))
            batches = self.runner.multipart(files, root, total=10)
            self.assertEqual([9,1], [b['photo_count'] for b in batches])
            for batch in batches:
                self.assertLess(batch['bytes'], 150*1024*1024)
                message=BytesParser(policy=policy.default).parsebytes(
                    ('Content-Type: multipart/form-data; boundary='+batch['boundary']+'\r\n\r\n').encode()+Path(batch['path']).read_bytes())
                parts = list(message.iter_parts())
                photos = [p for p in parts if p.get_param('name',header='content-disposition')=='attachments[]']
                self.assertEqual(batch['photo_count'], len(photos))
            self.assertFalse(batches[0]['complete']); self.assertTrue(batches[1]['complete'])

    def test_s5_schedules_and_s7_recovery_use_disjoint_fixtures(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); photo=root/'p.jpg';photo.write_bytes(b'photo')
            entry=dict(path=str(photo),mime_type='image/jpeg',width=1,height=1)
            config=dict(self.config(),scenario='S5',n=10,iterations=3,files=[entry],small_files=[entry],small_start_seconds=[.1,.2])
            templates,phases,count=self.runner.plan_phases(config,root/'s5')
            self.assertEqual(9,count)
            self.assertEqual(3,len([p for p in phases if p['variant']=='main']))
            self.assertEqual([0,3,6],[p['offset'] for p in phases if p['variant']=='main'])
            config=dict(self.config(),scenario='S7',n=5,iterations=10,files=[entry],recovery_iterations=20)
            templates,phases,count=self.runner.plan_phases(config,root/'s7')
            self.assertEqual(30,count)
            self.assertEqual('recovery',phases[-1]['variant'])
            self.assertEqual(10,phases[-1]['offset'])

    def test_summary_keeps_failure_timeout_and_incomplete_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events=[]
            for i,outcome in enumerate(('success','success','failure','failure',None)):
                events.append(dict(batch_id=str(i),stage='submit',start_ns=i*1000000000,end_ns=i*1000000000,photo_count=1,epoch_ms=i*1000,outcome='attempt'))
                events.append(dict(batch_id=str(i),stage='start',start_ns=i*1000000000,end_ns=i*1000000000+10,photo_count=1,epoch_ms=i*1000,outcome='accepted'))
                if outcome:
                    events.append(dict(batch_id=str(i),stage='photo_end',photo_index=0,start_ns=0,end_ns=(i+1)*1000000,photo_count=1,epoch_ms=i*1000,outcome=outcome))
                    events.append(dict(batch_id=str(i),stage='end',start_ns=0,end_ns=(i+1)*1000000,photo_count=1,epoch_ms=i*1000,outcome=outcome))
            (root/'worker-events.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in events))
            (root/'k6-raw.json').write_text(json.dumps(dict(type='Point',metric='http_timeout',data={'value':1,'tags':{}}))+'\n')
            result = self.runner.summarize(root)
            self.assertEqual(2, result['batch_success_ms']['count'])
            self.assertEqual(1, result['incomplete_batches'])
            self.assertEqual(1, result['http_timeouts'])
            self.assertEqual(2, result['committed_photos'])
            self.assertEqual(2, result['failed_batches'])

    def test_queued_batch_is_accepted_and_warmup_is_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            values=[dict(batch_id='run-warmup0-0-1',stage='submit',start_ns=0,end_ns=0,epoch_ms=0,photo_count=1,outcome='attempt'),
                    dict(batch_id='run-warmup0-0-1',stage='start',start_ns=0,end_ns=1,epoch_ms=0,photo_count=1,outcome='accepted'),
                    dict(batch_id='run-warmup0-0-1',stage='end',start_ns=0,end_ns=2,epoch_ms=0,photo_count=1,outcome='success'),
                    dict(batch_id='run-measurement0-1-1',stage='submit',start_ns=0,end_ns=0,epoch_ms=0,photo_count=5,outcome='attempt')]
            (root/'worker-events.jsonl').write_text(''.join(json.dumps(v)+'\n' for v in values))
            result=self.runner.summarize(root)
            self.assertEqual(1,result['accepted_batches'])
            self.assertEqual(5,result['accepted_photos'])
            self.assertEqual(1,result['incomplete_batches'])
            self.assertEqual(0,result['successful_batches'])

    def test_partial_convert_and_http_timeout_keep_server_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            values=[dict(batch_id='b',stage='submit',start_ns=0,end_ns=0,epoch_ms=0,photo_count=1,outcome='attempt'),
                    dict(batch_id='b',stage='start',start_ns=0,end_ns=1,epoch_ms=0,photo_count=1,outcome='accepted'),
                    dict(batch_id='b',stage='photo_start',photo_index=0,start_ns=0,end_ns=1,epoch_ms=0,photo_count=1,outcome='attempt'),
                    dict(batch_id='b',stage='convert_analyze',photo_index=0,start_ns=0,end_ns=1000000,epoch_ms=0,photo_count=1,outcome='success'),
                    dict(batch_id='b',stage='photo_end',photo_index=0,start_ns=0,end_ns=2000000,epoch_ms=0,photo_count=1,outcome='failure'),
                    dict(batch_id='b',stage='end',start_ns=0,end_ns=3000000,epoch_ms=0,photo_count=1,outcome='failure')]
            (root/'worker-events.jsonl').write_text(''.join(json.dumps(v)+'\n' for v in values))
            (root/'client-events.jsonl').write_text(json.dumps(dict(event='client_outcome',request_id='b',error_code=1050,status=0,phase='measurement'))+'\n')
            result=self.runner.summarize(root)
            self.assertEqual(0,result['photo_convert_sum_ms']['count'])
            self.assertEqual(1,result['photo_partial_convert_ms']['count'])
            self.assertEqual('failure',result['client_server_results'][0]['server_outcome'])
            self.assertTrue(result['client_server_results'][0]['http_timeout'])

    def test_normal_run_rejects_partial_or_blank_collectors_and_auth_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'resources.csv').write_text('epoch_ms,memory_current,memory_peak,oom_kill,cpu_usage_usec\n1,,,,\n')
            (root/'k6-raw.json').write_text(json.dumps(dict(type='Point',metric='http_401',data={'value':1,'tags':{}}))+'\n')
            result=self.runner.summarize(root)
            self.assertIn('container_cgroup_fields',result['missing_collectors'])
            self.assertIn('http_401',result['invalid_reasons'])

    def test_expected_error_does_not_hide_normal_worker_failure(self):
        result=dict(identities_valid=True,missing_collectors=[],invalid_reasons=[],incomplete_batches=0,residual_pids=[],
                    failed_batches=1,successful_batches=0,k6={},workloads={'recovery':{'failed_batches':1}})
        manifest=dict(drain=dict(idle=True,valid=True))
        self.assertFalse(self.runner.assess_run(dict(scenario='S0'),manifest,result))
        result['invalid_reasons']=[]
        self.assertFalse(self.runner.assess_run(dict(scenario='S7'),manifest,result))
        result['invalid_reasons']=[]
        result['workloads']['recovery']['failed_batches']=0
        self.assertTrue(self.runner.assess_run(dict(scenario='S7'),manifest,result))

    def test_interrupted_raw_json_is_invalid_and_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);raw=root/'k6-raw.json';raw.write_text('{"type":"Point",')
            result=self.runner.summarize(root)
            self.assertIn('raw_json_corrupt',result['invalid_reasons'])
            self.assertEqual('{"type":"Point",',raw.read_text())

    def test_cancelled_photo_and_batch_are_separate_from_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            values=[dict(batch_id='b',stage=stage,start_ns=0,end_ns=1,epoch_ms=0,photo_count=2 if stage in ('submit','end') else 1,photo_index=0,outcome='cancelled' if stage in ('photo_end','end') else 'attempt')
                    for stage in ('submit','start','photo_start','photo_end','end')]
            (root/'worker-events.jsonl').write_text(''.join(json.dumps(v)+'\n' for v in values))
            result=self.runner.summarize(root)
            self.assertEqual(1,result['cancelled_batches']);self.assertEqual(1,result['cancelled_photos'])
            self.assertEqual(0,result['failed_batches']);self.assertEqual(0,result['failed_photos'])
            self.assertEqual(1,result['unexecuted_photos']);self.assertTrue(result['identities_valid'])

class PipelineTest(unittest.TestCase):
    def test_pipeline_links_boundaries_and_excludes_intermediate_warmup_and_failed_inputs(self):
        import run
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); events=[]; clients=[]
            cases=[('final',True,'success'),('middle',False,'success'),('failed',True,'failure'),('pending',True,None),('r-warmup-0',True,'success')]
            for batch,final,outcome in cases:
                for stage,t in [('originals_saved',100),('submit',110),('start',120)]:
                    events.append(dict(batch_id=batch,stage=stage,start_ns=t,end_ns=t,epoch_ms=1,photo_count=1,outcome='success'))
                if outcome:
                    for stage,t in [('end',200),('batch_checkpoint',210)]:
                        events.append(dict(batch_id=batch,stage=stage,start_ns=110,end_ns=t,epoch_ms=1,photo_count=1,outcome=outcome))
                if batch in ('final','r-warmup-0'):
                    for stage,t in [('ai_request_start',250),('ai_request_received',260)]:
                        events.append(dict(batch_id=batch,stage=stage,start_ns=t,end_ns=t,epoch_ms=1,photo_count=0,outcome='success',execution_id='e'))
                clients.append(dict(request_id=batch,event='client_start',complete=final,phase='warmup' if 'warmup' in batch else 'measurement'))
            (root/'worker-events.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in events))
            (root/'client-events.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in clients))
            result=run.summarize(root); pipeline=result['pipeline']
            self.assertEqual(0.0001,pipeline['batch_ready_ms']['success']['p50'])
            self.assertEqual(0.00015,pipeline['ai_ready_ms']['p50'])
            self.assertEqual(0.00005,pipeline['post_derivative_ms']['p50'])
            self.assertEqual(dict(expected=1,started=1,received=1,missing=0,duplicate=0,incomplete=1,intermediate=1,pre_ai_failure=1),pipeline['ai_counts'])
            self.assertEqual(3,result['accepted_photos']+result['rejected_photos']-result['incomplete_batches'])
            self.assertEqual(0.0001,pipeline['batch_ready_ms']['success']['max'])

    def test_missing_and_duplicate_ai_receipts_are_reported(self):
        import run
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            events=[dict(batch_id=b,stage=stage,start_ns=0,end_ns=t,epoch_ms=1,photo_count=1,outcome='success')
                for b in ('one','two') for stage,t in [('originals_saved',1),('submit',2),('end',3),('batch_checkpoint',4),('ai_request_start',5)]]
            events += [dict(batch_id='two',stage='ai_request_received',start_ns=6,end_ns=6,epoch_ms=1,photo_count=0,outcome='success')]*2
            (root/'worker-events.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in events))
            (root/'client-events.jsonl').write_text(''.join(json.dumps(dict(request_id=b,event='client_outcome',complete=True,status=200))+'\n' for b in ('one','two')))
            counts=run.summarize(root)['pipeline']['ai_counts']
            self.assertEqual(1,counts['missing']);self.assertEqual(1,counts['duplicate'])

    def test_comparison_rejects_different_input_rate_or_resource_limits(self):
        import run
        baseline=dict(config=dict(rate=2,time_unit_seconds=10,n=5,http_seconds=50),photos=[dict(sha256='abc')],container_limits='cpu=2',tools='v1')
        self.assertTrue(run.compare_runs(baseline,baseline)['valid'])
        for patch in (dict(config=dict(baseline['config'],rate=3)),dict(photos=[dict(sha256='def')]),dict(container_limits='cpu=4')):
            self.assertFalse(run.compare_runs(dict(baseline,**patch),baseline)['valid'])

class PipelineCoverageTest(unittest.TestCase):
    def test_missing_link_measurements_cannot_pass_new_plan(self):
        import run
        result=dict(identities_valid=True,missing_collectors=[],invalid_reasons=[],incomplete_batches=0,residual_pids=[],
            failed_batches=0,successful_batches=1,k6={},pipeline=dict(ai_counts=dict(expected=1,started=1,received=1),unavailable=[dict(stage='attachment_save')]))
        self.assertFalse(run.assess_run(dict(scenario='S0',scenario_plan='2026-10-02'),dict(drain=dict(idle=True,valid=True)),result))

class FaultCoverageTest(unittest.TestCase):
    def test_error_scenario_requires_observed_failure_and_successful_recovery(self):
        import run
        result=dict(identities_valid=True,missing_collectors=[],invalid_reasons=[],incomplete_batches=0,residual_pids=[],
            failed_batches=0,successful_batches=2,k6={},workloads=dict(recovery=dict(failed_batches=0,committed_photos=5)),
            pipeline=dict(ai_counts=dict(expected=2,started=2,received=2),unavailable=[]))
        manifest=dict(drain=dict(idle=True,valid=True))
        self.assertFalse(run.assess_run(dict(scenario='S7',scenario_plan='2026-10-02'),manifest,result))
        result.update(failed_batches=1,successful_batches=1,invalid_reasons=[])
        self.assertTrue(run.assess_run(dict(scenario='S7',scenario_plan='2026-10-02'),manifest,result))

    def test_nonexistent_fault_photo_is_rejected_before_load(self):
        import quick,run
        entries=[dict(label=f'{fmt}-{size}',path=__file__,mime_type=mime,width=1,height=1)
            for fmt,mime in [('jpg','image/jpeg'),('png','image/png'),('heic','image/heic')] for size in ('low','high')]
        config=quick.build_plan(entries,'2026-10-02','S7')[0][1]
        config['fault']['photo_index']=5
        with self.assertRaises(ValueError):run.validate_config(config,check_files=False)

class PhaseGroupingTest(unittest.TestCase):
    def test_distinct_probe_rates_keep_separate_stage_and_queue_samples(self):
        import run
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);events=[];clients=[]
            for batch,phase,queue,ms in [('low','measurement0',0,1),('high','measurement1',2,5)]:
                clients.append(dict(request_id=batch,event='client_outcome',phase='measurement',phase_name=phase,complete=True))
                for stage in ('submit','start','convert_preview','end'):
                    events.append(dict(batch_id=batch,stage=stage,start_ns=0,end_ns=ms*1000000,epoch_ms=1,photo_count=1,photo_index=0,format='image/jpeg',mp=1,outcome='success',queue_size=queue))
            (root/'client-events.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in clients))
            (root/'worker-events.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in events))
            result=run.summarize(root)
            self.assertEqual(1,result['stages']['measurement0|image/jpeg|1|convert_preview|success']['p50'])
            self.assertEqual(5,result['stages']['measurement1|image/jpeg|1|convert_preview|success']['p50'])
            self.assertEqual(0,result['phase_stats']['measurement0']['queue_max'])
            self.assertEqual(2,result['phase_stats']['measurement1']['queue_max'])

class DeadlinePolicyTest(unittest.TestCase):
    def test_new_submission_cutoff_reserves_cleanup_and_never_exceeds_480(self):
        import run
        self.assertEqual(1480,run.submission_deadline(1000,dict(total_budget_seconds=600,drain_seconds=30,validation_seconds=40,cleanup_seconds=30)))
        self.assertEqual(1300,run.submission_deadline(1000,dict(total_budget_seconds=400,drain_seconds=30,validation_seconds=40,cleanup_seconds=30)))

    def test_phase_control_applies_delay_after_warmup_and_requires_matching_ack(self):
        from unittest.mock import patch
        import run
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'phase-applied.json').write_text(json.dumps(dict(phase='warmup')))
            clock=[0]
            def sleep(_):
                clock[0]+=1;(root/'phase-applied.json').write_text(json.dumps(dict(phase='measurement0')))
            with patch.object(run.time,'monotonic',side_effect=lambda:clock[0]),patch.object(run.time,'sleep',side_effect=sleep):
                run.apply_phase_control(root,dict(name='measurement0',phase='measurement'),dict(ai_ready_delay_ms=500),10)
            self.assertEqual(dict(phase='measurement0',ai_ready_delay_ms=500),json.loads((root/'phase-control.json').read_text()))
            self.assertEqual(1,clock[0])

if __name__ == '__main__':
    unittest.main()
