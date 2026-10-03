#!/usr/bin/env python3
"""Seven local bottleneck probes: eight minutes load, two minutes cleanup."""
import argparse
import json
import copy
import math
from pathlib import Path
import subprocess
import sys
import time
import uuid

import run

HERE = Path(__file__).resolve().parent


def build_plan(entries, plan_version="legacy", scenario=None, purpose="probe", baseline=None):
    if plan_version != "legacy":
        return versioned_plan(entries, plan_version, scenario, purpose, baseline)
    photos = {entry['label']: entry for entry in entries}
    base = dict(iterations=1, vus=1, bootstrap_seconds=45, http_seconds=55,
                max_duration_seconds=45, graceful_seconds=10, warmup_seconds=0,
                drain_seconds=30, validation_seconds=30, total_budget_seconds=600,
                token_ttl_seconds=3600)
    plans = [(f'S1-{fmt}', dict(base, scenario='S1', n=1, iterations=2,
              files=[photos[f'{fmt}-high']])) for fmt in ('jpg','png','heic')]
    plans.append(('S4-burst', dict(base, scenario='S4', n=5, vus=4, bursts=1,
                                 files=[photos['heic-high']])))
    plans.append(('S5-large-small', dict(base, scenario='S5', n=10,
        files=[photos['heic-high']], small_files=[photos['jpg-low']], small_start_seconds=[.5,1])))
    mixed = [photos[name] for name in ('jpg-low','jpg-low','png-low','heic-low','heic-low')]
    for stage in ('original_read','put_preview'):
        plans.append((f'S6-{stage}', dict(base, scenario='S6', n=5, http_seconds=30,
            files=mixed, rate=1, time_unit_seconds=10, measurement_seconds=20,
            preallocated_vus=4, max_vus=4,
            fault=dict(stage=stage, kind='delay', photo_index=0, delay_ms=500))))
    return plans


def read_baseline(path):
    path=Path(path)
    root=path if path.is_dir() else path.parent
    manifest=json.loads((root/'manifest.json' if path.is_dir() else path).read_text())
    manifest['results']=json.loads((root/'results.json').read_text())
    manifest['baseline_path']=str(root.resolve())
    return manifest


def versioned_plan(entries, version, scenario, purpose, baseline):
    if version!='2026-10-02' or scenario not in ['S'+str(i) for i in range(8)]:
        raise ValueError('new plan requires --scenario S0..S7 and --plan-version 2026-10-02')
    if purpose not in ('smoke','probe','compare','verify'):raise ValueError('invalid purpose')
    if purpose in ('compare','verify') and not baseline:raise ValueError('compare/verify requires baseline')
    if isinstance(baseline,(str,Path)):baseline=read_baseline(baseline)
    photos={e['label']:e for e in entries}
    if baseline and not baseline['config'].get('files'):
        selected=[]
        for old in baseline.get('photos',[]):
            candidates=[e for e in entries if e.get('sha256')==old.get('sha256') and e.get('label')==old.get('label')]
            if not candidates:raise ValueError('baseline input missing from manifest')
            selected.append(candidates[0])
        baseline=copy.deepcopy(baseline);baseline['config']['files']=selected
    duration={'smoke':45,'probe':120,'compare':90,'verify':180}[purpose]
    base=dict(iterations=1,vus=1,bootstrap_seconds=60,http_seconds=50,max_duration_seconds=duration,
        measurement_seconds=duration,graceful_seconds=10,warmup_seconds=0 if purpose=='smoke' or scenario=='S0' else 30,
        drain_seconds=30,validation_seconds=40,cleanup_seconds=30,total_budget_seconds=600,token_ttl_seconds=3600,
        command_seconds=120,scenario_plan=version,scenario_id=scenario,purpose=purpose,fixture_capacity=120,
        preallocated_vus=4,max_vus=4,rate=1,time_unit_seconds=10,files=[photos['heic-high']],n=5)
    names={'S0':'instrumentation','S1':'single-photo','S2':'batch-size','S3':'arrival','S4':'large-small','S5':'storage-delay','S6':'ai-ready','S7':'failure-recovery'}
    base['scenario_name']=names[scenario]
    models={'S4':'S5','S5':'S6','S6':'S3'};base['scenario']=models.get(scenario,scenario)
    configs=[]
    if purpose in ('compare','verify'):
        source=baseline['config']
        if source.get('executor')=='constant-vus':raise ValueError('compare requires a fixed-arrival baseline; closed model throughput cannot be replayed as a default rate')
        if source.get('scenario_id')!=scenario:raise ValueError('baseline scenario differs')
        selected=source.get('files')
        if not selected:
            selected=[]
            for old in baseline['photos']:
                candidates=[e for e in entries if e.get('sha256')==old.get('sha256') and e.get('label')==old.get('label')]
                if not candidates:raise ValueError('baseline input missing from manifest')
                selected.append(candidates[0])
        config=dict(copy.deepcopy(source),files=copy.deepcopy(selected),purpose=purpose,baseline_run_id=baseline['run_id'],
            baseline_path=baseline.get('baseline_path'),measurement_seconds=duration,max_duration_seconds=duration,total_budget_seconds=600)
        if source.get('small_start_seconds') and not config.get('small_files'):
            selected_small=[]
            for old in baseline.get('small_photos',[]):
                candidates=[e for e in entries if e.get('sha256')==old.get('sha256') and e.get('label')==old.get('label')]
                if not candidates:raise ValueError('baseline small input missing from manifest')
                selected_small.append(candidates[0])
            config['small_files']=selected_small
        if config.get('arrival_steps'):
            # Use one explicitly selected bottleneck rate, never repeat the increasing probe.
            selected_step=baseline.get('bottleneck_rate')
            if not selected_step:raise ValueError('baseline probe requires bottleneck_rate selection before comparison')
            config.pop('arrival_steps');config.update(selected_step)
        if config['scenario'] in ('S0','S1','S2','S6','S3'):
            config['executor']='constant-arrival-rate'
        configs=[config]
    elif scenario in ('S0','S1'):
        for fmt in ('jpg','png','heic'):
            for size in (('high',) if scenario=='S0' else ('low','high')):
                configs.append(dict(base,n=1,files=[photos[f'{fmt}-{size}']],executor='per-vu-iterations' if scenario=='S0' or purpose=='smoke' else 'constant-vus'))
    elif scenario=='S2':
        configs=[dict(base,n=n,executor='constant-vus' if purpose!='smoke' else 'per-vu-iterations') for n in (1,5,10)]
    elif scenario=='S3':
        if not baseline:raise ValueError('S3 probe requires measured S2 N=5 baseline')
        result=baseline.get('results',{});seconds=result.get('worker_execution_seconds',0)
        if baseline['config'].get('scenario_id')!='S2' or baseline['config'].get('n')!=5 or seconds<=0 or result.get('committed_photos',0)<=0:
            raise ValueError('S3 needs successful S2 execution samples')
        mu0=result['committed_photos']/seconds
        steps=[dict(rate=max(1,round(mu0*factor/5*60)),time_unit_seconds=60,duration=40) for factor in (.5,.8,1.2)]
        configs=[dict(base,files=baseline['config'].get('files',base['files']),arrival_steps=steps,mu0_photos_per_second=mu0)]
    elif scenario=='S4':
        configs=[dict(base,scenario='S5',n=1,files=[photos['jpg-low']],small_files=[photos['jpg-low']],small_start_seconds=[.5,1],iterations=1),
            dict(base,n=10,small_files=[photos['jpg-low']],small_start_seconds=[.5,1],iterations=1)]
        configs[0].update(scenario='S1',executor='constant-vus' if purpose!='smoke' else 'per-vu-iterations',scenario_name='small-control')
    elif scenario=='S5':
        configs=[dict(base,fault=None)]+[dict(base,fault=dict(stage=stage,kind='delay',photo_index=0,delay_ms=500)) for stage in ('original_read','put_preview')]
    elif scenario=='S6':configs=[dict(base,ai_ready_delay_ms=delay) for delay in (0,30000)]
    elif scenario=='S7':
        configs=[dict(base,error_max_duration_seconds=60,fault=dict(stage=stage,kind='error',photo_index=2),recovery_iterations=1) for stage in ('original_read','put_preview')]
    if purpose=='smoke' or scenario=='S0':
        for config in configs:config.update(http_seconds=50,max_duration_seconds=45,measurement_seconds=45)
    repeat=3 if purpose=='verify' else 1
    return [(f'{scenario}-{i+1}-repeat{r+1}',copy.deepcopy(config)) for i,config in enumerate(configs) for r in range(repeat)]


def execute(plans, output, load_seconds=480, cleanup_seconds=120,
            minimum_seconds=160, command=None, independent=False):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    load_deadline = started + load_seconds
    deadline = load_deadline + cleanup_seconds
    status = dict(complete=False, conditions=[])
    stopped = None
    for label, original in plans:
        row = dict(label=label)
        status['conditions'].append(row)
        if stopped:
            row['status'] = stopped
            continue
        if independent:
            child_start=time.monotonic();load_deadline=child_start+load_seconds;deadline=load_deadline+cleanup_seconds
        remaining = load_deadline - time.monotonic()
        if remaining < minimum_seconds or remaining <= 0:
            row['status'] = stopped = 'skipped_budget'
            continue
        config = dict(original, total_budget_seconds=original.get('total_budget_seconds',600) if independent else deadline-time.monotonic())
        config_path = output/(label+'.json')
        run.atomic_json(config_path, config, private=True)
        row['log'] = str(output/(label+'.log'))
        child = None
        try:
            with Path(row['log']).open('w') as log:
                child = subprocess.Popen(command or [sys.executable,str(HERE/'run.py'),'--config',str(config_path)],
                                         cwd=run.ROOT, stdout=log, stderr=subprocess.STDOUT)
                try:
                    row['exit_code'] = child.wait(timeout=max(.01,(deadline if independent else load_deadline)-time.monotonic()))
                    row['status'] = 'passed' if row['exit_code'] == 0 else 'failed'
                except (subprocess.TimeoutExpired, KeyboardInterrupt):
                    child.terminate()  # run.py handles SIGTERM and drains its worker.
                    row['status'] = 'cleanup_pending' if independent else 'interrupted'
                    stopped = 'skipped_budget'
                    try:
                        row['exit_code'] = child.wait(timeout=max(.01,deadline-time.monotonic()))
                    except subprocess.TimeoutExpired:
                        row.update(status='cleanup_pending', pid=child.pid)
                if row['status'] != 'passed' and not stopped:
                    stopped = 'skipped_previous_failure'
        except OSError as error:
            row.update(status='failed', error=str(error))
            stopped = 'skipped_previous_failure'
        status['elapsed_seconds'] = time.monotonic()-started
        run.atomic_json(output/'status.json', status)
    status.update(complete=all(row['status']=='passed' for row in status['conditions']),
                  elapsed_seconds=time.monotonic()-started)
    run.atomic_json(output/'status.json', status)
    return status


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=run.ROOT.parent/'performance-results/derivative/2026-10-01/functional-input-manifest.json')
    parser.add_argument('--execute', action='store_true', help='Run probes; default only validates and prints the plan')
    parser.add_argument('--plan-version',default='legacy',choices=['legacy','2026-10-02'])
    parser.add_argument('--scenario',choices=['S'+str(i) for i in range(8)])
    parser.add_argument('--purpose',default='probe',choices=['smoke','probe','compare','verify'])
    parser.add_argument('--baseline',type=Path)
    args = parser.parse_args()
    entries=json.loads(args.manifest.read_text())
    for entry in entries:
        entry['sha256']=run.hashlib.sha256(Path(entry['path']).read_bytes()).hexdigest()
    plans = build_plan(entries,args.plan_version,args.scenario,args.purpose,args.baseline)
    for _, config in plans:
        run.validate_config(config)
    if args.execute:
        output = HERE/'runtime'/('quick-'+uuid.uuid4().hex[:8])
        status = execute(plans, output,independent=args.plan_version!='legacy')
        print(json.dumps(dict(status_file=str(output/'status.json'), **status), ensure_ascii=False))
        sys.exit(0 if status['complete'] else 1)
    print(json.dumps([dict(label=label, **config) for label,config in plans], ensure_ascii=False, indent=2))
