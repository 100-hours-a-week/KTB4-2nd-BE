#!/usr/bin/env python3
"""Local-only derivative load runner; no third party Python dependencies."""
import argparse
import csv
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import resource
import shutil
import signal
import subprocess
import sys
import time
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo
from urllib.request import urlopen
from urllib.parse import urlencode

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def atomic_json(path, value, private=False):
    path = Path(path)
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    if private:
        temp.chmod(0o600)
    temp.replace(path)


def positive(config, key, zero=False):
    value = config.get(key)
    if isinstance(value, bool) or not isinstance(value, (int,float)) or not math.isfinite(value) or value < 0 or (not zero and value == 0):
        raise ValueError('finite positive budget/value required: ' + key)
    return value


def validate_config(config, check_files=True):
    if config.get('scenario') not in ['S'+str(i) for i in range(10)]:
        raise ValueError('scenario must be S0..S9')
    for key in ('bootstrap_seconds','http_seconds','drain_seconds','total_budget_seconds','token_ttl_seconds'):
        positive(config,key)
    positive(config,'graceful_seconds',zero=True)
    warmup=positive(config,'warmup_seconds',zero=True)
    n=config.get('n')
    if not isinstance(n,int) or isinstance(n,bool) or n < 1 or n > (200 if config['scenario']=='S9' else 10):
        raise ValueError('invalid photo count')
    if config.get('scenario_plan')=='2026-10-02':
        validate_versioned(config)
    arrival=config['scenario'] in ('S3','S6','S8') or config.get('executor')=='constant-arrival-rate'
    if arrival:
        duration=positive(config,'measurement_seconds')
        for key in ('rate','time_unit_seconds','preallocated_vus','max_vus'):
            positive(config,key)
        if not isinstance(config['rate'],int) or config['preallocated_vus'] > config['max_vus']:
            raise ValueError('invalid arrival allocation')
    else:
        positive(config,'iterations'); duration=positive(config,'max_duration_seconds')
        positive(config,'vus')
        if config['scenario'] in ('S0','S1','S2','S7') and config['vus'] != 1:
            raise ValueError('baseline/error scenarios require VU=1')
    phases=config.get('bursts',20) if config['scenario']=='S4' else config['iterations'] if config['scenario']=='S5' else 2 if config['scenario']=='S7' else 1
    if not isinstance(phases,int) or phases < 1:
        raise ValueError('invalid bursts')
    validation=positive(config,'validation_seconds') if 'validation_seconds' in config else config['drain_seconds']
    warmup_formats=len({e.get('mime_type') for e in config.get('files',[])}) if warmup else 0
    budget=config['bootstrap_seconds']+warmup+warmup_formats*(config['graceful_seconds']+config['drain_seconds'])+phases*(duration+config['graceful_seconds']+config['drain_seconds'])+validation
    if arrival and warmup:
        budget+=config.get('warmup_baseline_seconds',warmup)+config['graceful_seconds']+config['drain_seconds']
    if config.get('scenario_plan')=='2026-10-02':
        phase_specs=versioned_phases(config)
        groups={}
        for phase in phase_specs:
            key=phase.get('schedule',phase['name']);groups[key]=max(groups.get(key,0),phase.get('start_seconds',0)+phase.get('duration',phase.get('max_duration_seconds',0)))
        budget=config['bootstrap_seconds']+sum(value+config['graceful_seconds']+config['drain_seconds'] for value in groups.values())+validation+config.get('cleanup_seconds',30)
        input_budget=config['bootstrap_seconds']+sum(value+config['graceful_seconds']+config['drain_seconds'] for value in list(groups.values())[:-1])+list(groups.values())[-1]
        if input_budget>480:raise ValueError('new submissions would exceed 480 seconds')
    if config['total_budget_seconds'] < budget or config['token_ttl_seconds'] <= config['total_budget_seconds']:
        raise ValueError('total budget or token validity too short')
    if config['http_seconds'] > duration+config['graceful_seconds']:
        raise ValueError('HTTP limit exceeds scenario completion budget')
    if config['scenario']=='S5':
        if not config.get('small_files') or not config.get('small_start_seconds'):
            raise ValueError('S5 requires small_files and fixed small_start_seconds schedule')
        if max(config['small_start_seconds']) >= duration:
            raise ValueError('S5 schedule exceeds scenario budget')
    if (config['scenario']=='S7' or config['scenario']=='S6' and config.get('scenario_plan')!='2026-10-02') and not config.get('fault'):
        raise ValueError('S6/S7 require explicit fault')
    fault=config.get('fault')
    if fault and (fault.get('stage') not in ('original_read','put_analyze','put_preview','put_display') or
                  fault.get('kind') not in ('delay','error') or fault.get('photo_index',-1) < 0 or
                  fault.get('delay_ms',0) < 0):
        raise ValueError('invalid fault stage/kind/index')
    if fault:
        index=fault.get('photo_index')
        if not isinstance(index,int) or isinstance(index,bool) or index>=n:raise ValueError('fault photo index outside batch')
        positive(dict(delay_ms=fault.get('delay_ms',0)),'delay_ms',zero=True)
    if check_files:
        for files in (config.get('files',[]),config.get('small_files',[])):
            for entry in files:
                path=Path(entry['path']).resolve()
                if not path.is_file() or path.stat().st_size > 15*1024*1024 or path.stat().st_size == 0:
                    raise ValueError('missing/oversize photo: '+str(path))
                if entry.get('mime_type') not in ('image/jpeg','image/png','image/heic'):
                    raise ValueError('unsupported image format')
                if not entry.get('width',0)>0 or not entry.get('height',0)>0:
                    raise ValueError('dimensions required')
                digest=hashlib.sha256(path.read_bytes()).hexdigest()
                if entry.get('sha256') and entry['sha256'] != digest:
                    raise ValueError('photo hash mismatch')
                entry.update(path=str(path),sha256=digest,bytes=path.stat().st_size)
        if not config.get('files'):
            raise ValueError('actual photo manifest required')
    return config


def validate_versioned(config):
    if config.get('scenario_id') not in ['S'+str(i) for i in range(8)]:raise ValueError('invalid versioned scenario')
    if config['total_budget_seconds']>600:raise ValueError('run exceeds 600 seconds')
    purpose=config.get('purpose'); duration=positive(config,'measurement_seconds')
    limits={'smoke':(1,60),'probe':(120,180),'compare':(60,120),'verify':(180,300)}
    if purpose not in limits:raise ValueError('invalid purpose')
    if config['scenario_id']!='S0' and not limits[purpose][0]<=duration<=limits[purpose][1]:raise ValueError('measurement duration outside purpose policy')
    if config['scenario_id']!='S0' and purpose!='smoke' and not 30<=config['warmup_seconds']<=60:raise ValueError('warmup must be 30..60 seconds')
    if purpose in ('compare','verify') and not config.get('baseline_run_id'):raise ValueError('baseline required')
    if config.get('ai_ready_delay_ms',0)<0 or config.get('ai_ready_delay_ms',0)>180000:raise ValueError('invalid AI ready delay')
    for key in ('fixture_capacity','warmup_fixture_capacity'):
        if key in config and (not isinstance(config[key],int) or isinstance(config[key],bool) or config[key]<1):raise ValueError('invalid fixture capacity')
    for step in config.get('arrival_steps',[]):
        for key in ('rate','time_unit_seconds','duration'):positive(step,key)
        if not isinstance(step['rate'],int):raise ValueError('arrival rate must be integer')


def versioned_phases(config):
    phases=[]; offset=0
    def add(name,phase,variant,executor,duration,**options):
        nonlocal offset
        if executor=='constant-arrival-rate':count=math.ceil(options['rate']*duration/options['time_unit_seconds'])+1
        elif executor=='constant-vus':
            # ponytail: finite independent fixtures; raise capacity after observed exhaustion, never recycle completed trips.
            count=config.get('warmup_fixture_capacity',30) if phase=='warmup' else config.get('fixture_capacity',120)
        else:count=options.get('iterations',1)*options.get('vus',1)
        spec=dict(name=name,phase=phase,variant=variant,offset=offset,count=count,executor=executor,**options)
        if executor in ('constant-arrival-rate','constant-vus'):spec['duration']=duration
        else:spec['max_duration_seconds']=duration
        phases.append(spec);offset+=count
    if config['warmup_seconds']:
        add('warmup','warmup','main','constant-vus',config['warmup_seconds'],vus=1)
    scenario=config['scenario'];duration=config['measurement_seconds']
    if scenario=='S5':
        for i in range(config['iterations']):
            add('large'+str(i),'measurement','main','per-vu-iterations',duration,vus=1,iterations=1,schedule=i)
            for j,start in enumerate(config['small_start_seconds']):
                add(f'small{i}_{j}','measurement','small','per-vu-iterations',duration-start,vus=1,iterations=1,start_seconds=start,schedule=i)
    elif scenario in ('S3','S6') or config.get('executor')=='constant-arrival-rate':
        for i,step in enumerate(config.get('arrival_steps') or [dict(rate=config['rate'],time_unit_seconds=config['time_unit_seconds'],duration=duration)]):
            add('measurement'+str(i),'measurement','main','constant-arrival-rate',step['duration'],rate=step['rate'],time_unit_seconds=step['time_unit_seconds'],preallocated_vus=config['preallocated_vus'],max_vus=config['max_vus'])
    else:
        executor=config.get('executor','per-vu-iterations')
        options=dict(vus=config['vus'])
        if executor!='constant-vus':options['iterations']=config['iterations']
        add('measurement','measurement','main',executor,config.get('error_max_duration_seconds',duration) if scenario=='S7' else duration,**options)
    if scenario=='S7':add('recovery','measurement','recovery','shared-iterations',duration,vus=1,iterations=config.get('recovery_iterations',1))
    return phases


def multipart(files, directory, total=None):
    """Stream binary bodies; preserve repeated names and input order."""
    directory=Path(directory); directory.mkdir(parents=True,exist_ok=True)
    total=total or len(files)
    groups=[]; group=[]; size=0
    for entry in files:
        length=Path(entry['path']).stat().st_size
        if length>15*1024*1024:
            raise ValueError('file exceeds 15MiB')
        if group and (len(group)==10 or size+length>145*1024*1024):
            groups.append(group); group=[]; size=0
        group.append(entry); size+=length
    if group: groups.append(group)
    result=[]
    for index,group in enumerate(groups):
        boundary='yeodam-'+uuid.uuid4().hex
        path=directory/('batch-'+str(index+1)+'.bin')
        with path.open('wb') as stream:
            for photo in group:
                filename=Path(photo['path']).name
                if any(c in filename for c in ('"','\r','\n')): raise ValueError('unsafe filename')
                stream.write(('--'+boundary+'\r\nContent-Disposition: form-data; name="attachments[]"; filename="'+filename+'"\r\nContent-Type: '+photo['mime_type']+'\r\n\r\n').encode())
                with Path(photo['path']).open('rb') as source: shutil.copyfileobj(source,stream,1024*1024)
                stream.write(b'\r\n')
            for key,value in {'batchNo':index+1,'totalAttachmentCount':total,'complete':str(index==len(groups)-1).lower()}.items():
                stream.write(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+key+'"\r\n\r\n'+str(value)+'\r\n').encode())
            stream.write(('--'+boundary+'--\r\n').encode())
        path.chmod(0o600)
        length=path.stat().st_size
        if length>=150*1024*1024: raise ValueError('multipart exceeds Spring 150MB')
        result.append(dict(path=str(path),boundary=boundary,bytes=length,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                           photo_count=len(group),complete=index==len(groups)-1,photos=group))
    return result


def quantiles(values):
    values=sorted(values)
    def at(p):
        if not values:return None
        index=(len(values)-1)*p; lo=math.floor(index); hi=math.ceil(index)
        return values[lo]+(values[hi]-values[lo])*(index-lo)
    return dict(count=len(values),p50=at(.5),p95=at(.95),p99=at(.99),max=max(values,default=None),p99_exploratory=True,p99_sample_sufficient=len(values)>=1000)


def read_lines(path, errors=None):
    if path.exists():
        with path.open() as stream:
            for number,line in enumerate(stream,1):
                if not line.strip():continue
                try:yield json.loads(line)
                except json.JSONDecodeError:
                    if errors is None:raise
                    errors.append(path.name+':'+str(number))


def summarize(run_dir):
    root=Path(run_dir); parse_errors=[]; batches={}; stages={}; generated=0; failed_photos=0; cancelled_photos=0; attempted=0
    workloads={}; photo_convert={}; photo_outcomes={}; command_expected=Counter(); queue_max=0; accepted_photos=0; rejected_photos=0; start_epochs=[]; end_epochs=[]
    windows={10:{},60:{}}
    clients={};phase_stats={}
    for client in read_lines(root/'client-events.jsonl',parse_errors):
        if client.get('phase')=='warmup':continue
        clients.setdefault(client['request_id'],{}).update(client)
    for raw in read_lines(root/'worker-events.jsonl',parse_errors):
        event=raw.get('worker',raw); batch_id=event.get('batch_id'); stage=event['stage']
        if '-warmup' in str(batch_id): continue
        batch=batches.setdefault(batch_id,{})
        phase_name=clients.get(batch_id,{}).get('phase_name','measurement')
        stats=phase_stats.setdefault(phase_name,dict(queue_max=0,submitted_batches=0,rejected_batches=0))
        stats['queue_max']=max(stats['queue_max'],event.get('queue_size',0))
        if stage=='submit':stats['submitted_batches']+=1
        if stage=='rejected':stats['rejected_batches']+=1
        workload='recovery' if '-recovery-' in str(batch_id) else 'measurement'
        workload_counts=workloads.setdefault(workload,dict(attempted_photos=0,generated_photos=0,failed_photos=0,cancelled_photos=0,committed_photos=0,failed_batches=0))
        if stage=='photo_start':workload_counts['attempted_photos']+=1
        if stage=='photo_end':workload_counts[{'success':'generated_photos','failure':'failed_photos','cancelled':'cancelled_photos'}[event['outcome']]]+=1
        if stage=='end':
            if event['outcome']=='success':workload_counts['committed_photos']+=event['photo_count']
            elif event['outcome']=='failure':workload_counts['failed_batches']+=1
        if stage in ('submit','start','end','rejected'): batch[stage]=event
        batch.setdefault("events",{}).setdefault(stage,[]).append(event)
        queue_max=max(queue_max,event.get('queue_size',0))
        if stage=='start': accepted_photos+=event['photo_count']; start_epochs.append(event.get('epoch_ms',0))
        if stage=='rejected': rejected_photos+=event['photo_count']
        if stage=='end':end_epochs.append(event.get('epoch_ms',0))
        if stage=='photo_start': attempted+=1
        if stage=='photo_end':
            photo_outcomes[(batch_id,event.get('photo_index'))]=event['outcome']
            generated+=event['outcome']=='success'; failed_photos+=event['outcome']=='failure';cancelled_photos+=event['outcome']=='cancelled'
        if stage.startswith(('convert_','exif_','original_read','put_')):
            if stage.startswith(('convert_','exif_')):
                command_expected[(batch_id,str(event.get('photo_index')),stage)]+=1
            group='|'.join((phase_name,event.get('format','unknown'),str(event.get('mp','unknown')),stage,event['outcome']))
            ms=(event['end_ns']-event['start_ns'])/1e6
            stages.setdefault(group,[]).append(ms)
            if stage.startswith('convert_') and event['outcome']=='success':
                key=(batch_id,event.get('photo_index')); photo_convert[key]=photo_convert.get(key,0)+ms
        for length,table in windows.items():
            second=event.get('epoch_ms',0)//(length*1000)*length
            counts=table.setdefault(second,dict(submitted=0,accepted=0,generated=0,committed=0,rejected=0))
            if stage in ('submit','start','rejected'):counts[{'submit':'submitted','start':'accepted','rejected':'rejected'}[stage]]+=event['photo_count']
            if stage=='photo_end' and event['outcome']=='success':counts['generated']+=1
            if stage=='end' and event['outcome']=='success':counts['committed']+=event['photo_count']
    successful=[b['end'] for b in batches.values() if b.get('end',{}).get('outcome')=='success']
    failed=[b['end'] for b in batches.values() if b.get('end',{}).get('outcome')=='failure']
    cancelled=[b['end'] for b in batches.values() if b.get('end',{}).get('outcome')=='cancelled']
    accepted=[b for b in batches.values() if 'submit' in b and 'rejected' not in b]
    accepted_photos=sum(b['submit']['photo_count'] for b in accepted)
    rejected=sum('rejected' in b for b in batches.values())
    incomplete=sum('end' not in b for b in accepted)
    raw_k6={}; http_requests=0
    for point in read_lines(root/'k6-raw.json',parse_errors):
        if point.get('type')=='Point':
            metric=point['metric']; value=point['data']['value']
            if point['data'].get('tags',{}).get('phase')=='warmup':continue
            if metric in ('http_timeout','server_failure','fixture_exhausted','dropped_iterations','http_reqs','sent_photos','http_401','http_403','http_413'):
                raw_k6[metric]=raw_k6.get(metric,0)+value
    commands=list((root/'commands').glob('*.json')) if (root/'commands').exists() else []
    command_values=[json.loads(p.read_text()) for p in commands]
    command_values=[v for v in command_values if '-warmup' not in str(v.get('request_id'))]
    resource_values=[]
    if (root/'resources.csv').exists():
        with (root/'resources.csv').open() as stream:resource_values=list(csv.DictReader(stream))
    submitted=sum('submit' in b for b in batches.values()); unexecuted=max(0,accepted_photos-attempted)
    interval=(max(end_epochs)-min(start_epochs))/1000 if end_epochs and start_epochs else 0
    committed=sum(e['photo_count'] for e in successful)
    result=dict(submitted_batches=submitted,accepted_batches=len(accepted),rejected_batches=rejected,
                successful_batches=len(successful),failed_batches=len(failed),incomplete_batches=incomplete,
                accepted_photos=accepted_photos,rejected_photos=rejected_photos,attempted_photos=attempted,
                generated_photos=generated,committed_photos=committed,failed_photos=failed_photos,unexecuted_photos=unexecuted,
                cancelled_batches=len(cancelled),cancelled_photos=cancelled_photos,incomplete_photos=max(0,attempted-generated-failed_photos-cancelled_photos),photo_failure_rate=failed_photos/attempted if attempted else None,
                batch_success_ms=quantiles([(e['end_ns']-e['start_ns'])/1e6 for e in successful]),
                batch_failure_ms=quantiles([(e['end_ns']-e['start_ns'])/1e6 for e in failed]),
                queue_wait_ms=quantiles([(b['start']['end_ns']-b['start']['start_ns'])/1e6 for b in accepted if 'start' in b]),
                queue_max=queue_max,photo_convert_sum_ms=quantiles([value for key,value in photo_convert.items() if photo_outcomes.get(key)=='success']),
                photo_partial_convert_ms=quantiles([value for key,value in photo_convert.items() if photo_outcomes.get(key)!='success']),stages={k:quantiles(v) for k,v in stages.items()},
                http_timeouts=raw_k6.get('http_timeout',0),http_timeout_rate=raw_k6.get('http_timeout',0)/raw_k6['http_reqs'] if raw_k6.get('http_reqs') else None,
                k6=raw_k6,command_samples=len(command_values),command_timeouts=sum(s['timeout'] for s in command_values),
                command_cpu_ms=sum(s['user_cpu_ms']+s['system_cpu_ms'] for s in command_values),
                command_max_rss_bytes=max((s['max_rss_bytes'] for s in command_values),default=None),
                residual_pids=[p for s in command_values for p in s['residual_pids']],
                container_sampled_peak_bytes=max((int(s['memory_current']) for s in resource_values if s.get('memory_current')),default=None),
                container_kernel_peak_bytes=max((int(s['memory_peak']) for s in resource_values if s.get('memory_peak')),default=None),
                oom_delta=max((int(s['oom_kill']) for s in resource_values if s.get('oom_kill')),default=0)-min((int(s['oom_kill']) for s in resource_values if s.get('oom_kill')),default=0),
                observation_seconds=interval,completion_photos_per_second=committed/interval if interval>0 else None,
                windows=windows)
    result['identities_valid']=submitted==len(accepted)+rejected and len(accepted)==len(successful)+len(failed)+len(cancelled)+incomplete and attempted<=accepted_photos
    result['missing_collectors']=[]
    if not command_values:result['missing_collectors'].append('command_wait4')
    if not resource_values:result['missing_collectors'].append('container_cgroup')
    elif any(any(row.get(key) in ('',None) for key in ('epoch_ms','memory_current','oom_kill','cpu_usage_usec')) for row in resource_values):
        result['missing_collectors'].append('container_cgroup_fields')
    actual=Counter((v.get('request_id'),str(v.get('worker_photo_index')),v.get('stage')) for v in command_values)
    if actual!=command_expected:result['missing_collectors'].append('command_stage_coverage')
    phases=json.loads((root/'phases.json').read_text()) if (root/'phases.json').exists() else []
    valid_resources=[int(row['epoch_ms']) for row in resource_values if row.get('epoch_ms')]
    if phases and valid_resources:
        gaps=[b-a for a,b in zip(valid_resources,valid_resources[1:])]
        if min(valid_resources)>min(p['start_epoch_ms'] for p in phases)+500 or max(valid_resources)<max(p['end_epoch_ms'] for p in phases)-500 or max(gaps,default=0)>2000:
            result['missing_collectors'].append('resource_time_coverage')
    result['phase_stats']=phase_stats
    result['client_server_results']=[]
    for request,client in clients.items():
        server=batches.get(request,{})
        result['client_server_results'].append(dict(request_id=request,trip_id=client.get('trip_id'),
            http_timeout=client.get('error_code')==1050,http_status=client.get('status'),
            client_incomplete=client.get('event')=='client_start',
            server_outcome=server.get('end',{}).get('outcome','rejected' if 'rejected' in server else 'incomplete' if 'submit' in server else 'not_submitted')))
    result['pipeline']=summarize_pipeline(batches,clients)
    result['worker_execution_seconds']=sum((b['end']['end_ns']-b['start']['end_ns'])/1e9 for b in accepted if 'end' in b and 'start' in b and b['end']['outcome']=='success')
    result['workloads']=workloads
    for counts in workloads.values():counts['photo_failure_rate']=counts['failed_photos']/counts['attempted_photos'] if counts['attempted_photos'] else None
    command_groups={}
    for sample in command_values:
        key='|'.join((clients.get(sample.get('request_id'),{}).get('phase_name','measurement'),sample.get('worker_format') or 'unknown',sample.get('stage') or 'unknown','timeout' if sample['timeout'] else 'success' if sample.get('exit_code')==0 else 'failure'))
        values=command_groups.setdefault(key,dict(cpu_ms=[],wall_ms=[],rss_bytes=[]))
        values['cpu_ms'].append(sample['user_cpu_ms']+sample['system_cpu_ms']);values['wall_ms'].append(sample['wall_ms']);values['rss_bytes'].append(sample['max_rss_bytes'])
    result['command_resources']={group:{key:quantiles(values) for key,values in measures.items()} for group,measures in command_groups.items()}
    result['invalid_reasons']=[key for key in ('http_401','http_403','http_413','fixture_exhausted','dropped_iterations') if raw_k6.get(key,0)>0]
    if parse_errors:result['invalid_reasons'].append('raw_json_corrupt')
    result['raw_parse_errors']=parse_errors
    result['sample_shortfall']=max(0,1000-generated)
    result['window_photos_per_second']={str(length):{str(second):{kind:value/length for kind,value in counts.items()} for second,counts in table.items()} for length,table in windows.items()}
    result['input_and_drain']=[]
    for phase in phases:
        input_counts=dict(submitted=0,committed=0);drain_counts=dict(committed=0)
        for batch in batches.values():
            submitted_event=batch.get('submit',{})
            if phase['start_epoch_ms']<=submitted_event.get('epoch_ms',-1)<=phase['end_epoch_ms']:
                input_counts['submitted']+=submitted_event.get('photo_count',0)
                end=batch.get('end',{})
                if end.get('outcome')=='success':
                    if end.get('epoch_ms',0)<=phase['end_epoch_ms']:input_counts['committed']+=end['photo_count']
                    else:drain_counts['committed']+=end['photo_count']
        input_seconds=(phase['end_epoch_ms']-phase['start_epoch_ms'])/1000
        drain_seconds=(phase.get('drain_end_epoch_ms',phase['end_epoch_ms'])-phase['end_epoch_ms'])/1000
        result['input_and_drain'].append(dict(name=phase['name'],phase=phase['phase'],input_seconds=input_seconds,drain_seconds=drain_seconds,
                    input_counts=input_counts,drain_counts=drain_counts,
                    input_photos_per_second=input_counts['committed']/input_seconds if input_seconds>0 else None,
                    drain_photos_per_second=drain_counts['committed']/drain_seconds if drain_seconds>0 else None))
    atomic_json(root/'results.json',result)
    with (root/'results.csv').open('w') as stream:
        writer=csv.writer(stream); writer.writerow(['metric','value'])
        for key,value in result.items():writer.writerow([key,json.dumps(value)])
    return result


def summarize_pipeline(batches, clients):
    ready={'success':[], 'failure':[]}; ai=[]; post=[]; links={}; counts=dict(expected=0,started=0,received=0,missing=0,duplicate=0,incomplete=0,intermediate=0,pre_ai_failure=0)
    unavailable=[]
    for request,batch in batches.items():
        events=batch.get('events',{}); client=clients.get(request,{})
        original=next(iter(events.get('originals_saved',[])),None); end=batch.get('end')
        if original and end and end['outcome'] in ready:
            ready[end['outcome']].append((end['end_ns']-original['end_ns'])/1e6)
        for stage,samples in events.items():
            if stage in ('attachment_save','storage_retain','batch_checkpoint','processing_check','request_build','ai_ec2_ready','ai_health_ready'):
                for sample in samples:
                    key='|'.join((client.get('phase_name','measurement'),stage,sample['outcome']))
                    links.setdefault(key,[]).append((sample['end_ns']-sample['start_ns'])/1e6)
        starts=events.get('ai_request_start',[]); receipts=events.get('ai_request_received',[])
        counts['started']+=len(starts); counts['received']+=len(receipts)
        counts['duplicate']+=max(0,len(starts)-1)+max(0,len(receipts)-1)
        if starts:
            if original:ai.append((starts[0]['end_ns']-original['end_ns'])/1e6)
            else:unavailable.append(dict(request_id=request,stage='originals_saved'))
            if end:post.append((starts[0]['end_ns']-end['end_ns'])/1e6)
        if client.get('complete') is False:
            counts['intermediate']+=1;continue
        if client.get('complete') is not True and not starts:
            continue  # Legacy data does not establish a final-batch denominator.
        pre_failure='rejected' in batch or (end and end['outcome']!='success') or any(e['outcome']=='failure' for stage in ('attachment_save','storage_retain','batch_checkpoint','processing_check','request_build','ai_ec2_ready','ai_health_ready') for e in events.get(stage,[]))
        if pre_failure:
            counts['pre_ai_failure']+=1;continue
        if not starts and (not end or (client.get('event')=='client_start' and not events.get('batch_checkpoint'))):
            counts['incomplete']+=1;continue
        counts['expected']+=1
        for required in ('originals_saved','attachment_save','storage_retain','batch_checkpoint','processing_check','request_build','ai_ec2_ready','ai_health_ready'):
            if not events.get(required):unavailable.append(dict(request_id=request,stage=required))
        matched=[e for e in receipts if not starts or e.get('execution_id')==starts[0].get('execution_id')]
        counts['missing']+=int(not starts or not matched)
    return dict(batch_ready_ms={k:quantiles(v) for k,v in ready.items()},ai_ready_ms=quantiles(ai),
                post_derivative_ms=quantiles(post),stages={k:quantiles(v) for k,v in links.items()},
                ai_counts=counts,unavailable=unavailable,ai_latency_scope='Only requests reaching ai_request_start; receipt timestamps are not subtracted across processes')


def compare_runs(current, baseline):
    if baseline.get('config',{}).get('arrival_steps') and baseline.get('bottleneck_rate'):
        baseline=dict(baseline,config=dict(baseline['config'],**baseline['bottleneck_rate']))
        baseline['config'].pop('arrival_steps')
    def model(manifest):
        config=manifest.get('config',{})
        return config.get('executor','constant-arrival-rate' if config.get('scenario') in ('S3','S6') else 'per-vu-iterations')
    keys=('n','rate','time_unit_seconds','http_seconds','command_seconds','graceful_seconds','drain_seconds','warmup_seconds','ai_delay_ms','ai_ready_delay_ms','fault','preallocated_vus','max_vus','fixture_capacity','arrival_steps')
    mismatches=[key for key in keys if current.get('config',{}).get(key)!=baseline.get('config',{}).get(key)]
    if model(current)!=model(baseline):mismatches.append('executor')
    for key in ('photos','small_photos','container_limits','tools','docker_vm'):
        if current.get(key)!=baseline.get(key):mismatches.append(key)
    return dict(valid=not mismatches,status='comparable' if not mismatches else 'comparison_invalid',mismatches=mismatches,observed_input=dict(current=current.get('observed_input'),baseline=baseline.get('observed_input')))


def assess_run(config, manifest, result):
    expected_errors=config['scenario']=='S7'
    normal_failure=result['failed_batches']>0 and not expected_errors
    if not manifest.get('drain',{}).get('valid') and 'output_validation' not in result['invalid_reasons']:result['invalid_reasons'].append('output_validation')
    if config.get('scenario_plan')=='2026-10-02':
        counts=result.get('pipeline',{}).get('ai_counts',{})
        if not counts or counts.get('missing') or counts.get('duplicate') or counts.get('incomplete') or result.get('pipeline',{}).get('unavailable'):
            result['invalid_reasons'].append('ai_pipeline_coverage')
    if config.get('scenario_plan')=='2026-10-02' and expected_errors:
        if result['failed_batches']<1 or result.get('workloads',{}).get('recovery',{}).get('committed_photos',0)<1:
            result['invalid_reasons'].append('fault_recovery_not_observed')
    recovery_failure=result.get('workloads',{}).get('recovery',{}).get('failed_batches',0)>0
    if result.get('cancelled_batches',0) and 'server_cancelled' not in result['invalid_reasons']:result['invalid_reasons'].append('server_cancelled')
    if normal_failure and 'unexpected_worker_failure' not in result['invalid_reasons']:result['invalid_reasons'].append('unexpected_worker_failure')
    if recovery_failure and 'recovery_worker_failure' not in result['invalid_reasons']:result['invalid_reasons'].append('recovery_worker_failure')
    return bool(manifest.get('drain',{}).get('idle') and manifest.get('drain',{}).get('valid') and not manifest.get('error') and
                result['identities_valid'] and not result['missing_collectors'] and not result['invalid_reasons'] and
                not result['incomplete_batches'] and not result['residual_pids'])


def collect_resources(root):
    root=Path(root); cgroup=Path('/sys/fs/cgroup'); started=time.monotonic()
    def numbers(path):
        try:return dict(line.split() for line in path.read_text().splitlines())
        except OSError:return {}
    def number(name):
        try:return (cgroup/name).read_text().strip()
        except OSError:return ''
    with (root/'resources.csv').open('w',buffering=1) as stream:
        fields=['epoch_ms','elapsed_seconds','memory_current','memory_peak','oom_kill','cpu_usage_usec','throttled_usec','tmp_used_percent','java_rss_bytes']
        writer=csv.DictWriter(stream,fieldnames=fields);writer.writeheader()
        while not (root/'collector-stop.json').exists():
            cpu=numbers(cgroup/'cpu.stat'); memory=numbers(cgroup/'memory.events'); disk=shutil.disk_usage('/tmp')
            java_rss=0
            for process in Path('/proc').iterdir():
                if process.name.isdigit():
                    try:
                        if (process/'comm').read_text().strip()=='java':
                            status=(process/'status').read_text().splitlines()
                            java_rss+=sum(int(line.split()[1])*1024 for line in status if line.startswith('VmRSS:'))
                    except OSError:pass
            writer.writerow(dict(epoch_ms=int(time.time()*1000),elapsed_seconds=time.monotonic()-started,
                                 memory_current=number('memory.current'),memory_peak=number('memory.peak'),oom_kill=memory.get('oom_kill',''),
                                 cpu_usage_usec=cpu.get('usage_usec',''),throttled_usec=cpu.get('throttled_usec',''),
                                 tmp_used_percent=disk.used/disk.total*100,java_rss_bytes=java_rss))
            time.sleep(.2)


def wait_file(path, deadline, process=None):
    while time.monotonic()<deadline:
        if path.exists():return json.loads(path.read_text())
        if process is not None and process.poll() is not None:raise RuntimeError('server exited before '+path.name)
        time.sleep(.1)
    raise TimeoutError(path.name+' deadline')


def plan_phases(config, root):
    n=config['n']; scenario=config['scenario']; entries=config['files']
    selected=[entries[i%len(entries)] for i in range(n)]
    templates={'main':multipart(selected,root/'bodies'/'main',n)}
    if config.get('scenario_plan')=='2026-10-02':
        phases=versioned_phases(config)
        if scenario=='S5':templates['small']=multipart(config['small_files'][:1],root/'bodies'/'small',1)
        if scenario=='S7':templates['recovery']=templates['main']
        return templates,phases,sum(p['count'] for p in phases)
    phases=[]; offset=0
    if config.get('warmup_photos',20)>0 and config['warmup_seconds']>0:
        by_format={}
        for e in entries:by_format.setdefault(e['mime_type'],e)
        for index,e in enumerate(by_format.values()):
            key='warmup'+str(index); templates[key]=multipart([e],root/'bodies'/key,1)
            count=config.get('warmup_photos',20)
            phases.append(dict(name=key,phase='warmup',variant=key,offset=offset,count=count,
                               vus=1,iterations=count,max_duration_seconds=config.get('warmup_baseline_seconds',config['warmup_seconds'])/len(by_format),executor='shared-iterations'))
            offset+=count
        if scenario in ('S3','S6','S8'):
            count=math.ceil(config['rate']*config['warmup_seconds']/config['time_unit_seconds'])+1
            phases.append(dict(name='warmup_arrival',phase='warmup',variant='main',offset=offset,count=count,executor='constant-arrival-rate',
                               rate=config['rate'],time_unit_seconds=config['time_unit_seconds'],duration=config['warmup_seconds'],preallocated_vus=config['preallocated_vus'],max_vus=config['max_vus']))
            offset+=count
    if scenario=='S5':templates['small']=multipart(config['small_files'][:1],root/'bodies'/'small',1)
    if scenario in ('S3','S6','S8'):
        count=math.ceil(config['rate']*config['measurement_seconds']/config['time_unit_seconds'])+1
        phases.append(dict(name='measurement',phase='measurement',variant='main',offset=offset,count=count,executor='constant-arrival-rate',
                           rate=config['rate'],time_unit_seconds=config['time_unit_seconds'],duration=config['measurement_seconds'],
                           preallocated_vus=config['preallocated_vus'],max_vus=config['max_vus']))
        offset+=count
    elif scenario=='S5':
        for schedule in range(config['iterations']):
            phases.append(dict(name='large'+str(schedule),phase='measurement',variant='main',offset=offset,count=1,vus=1,iterations=1,executor='per-vu-iterations',max_duration_seconds=config['max_duration_seconds'],schedule=schedule))
            offset+=1
            for index,start in enumerate(config['small_start_seconds']):
                phases.append(dict(name='small'+str(schedule)+'_'+str(index),phase='measurement',variant='small',offset=offset,count=1,vus=1,iterations=1,executor='per-vu-iterations',start_seconds=start,max_duration_seconds=config['max_duration_seconds']-start,schedule=schedule));offset+=1

    else:
        repeats=config.get('bursts',20) if scenario=='S4' else 1
        for index in range(repeats):
            count=config['vus'] if scenario=='S4' else config['iterations']
            phases.append(dict(name='measurement'+str(index),phase='measurement',variant='main',offset=offset,count=count,vus=config['vus'],
                               iterations=1 if scenario=='S4' else count,executor='per-vu-iterations' if scenario=='S4' else 'shared-iterations',max_duration_seconds=config['max_duration_seconds']))
            offset+=count
    if scenario=='S7':
        templates['recovery']=templates['main']
        count=config.get('recovery_iterations',20)
        phases.append(dict(name='recovery',phase='measurement',variant='recovery',offset=offset,count=count,vus=1,
                           iterations=count,executor='shared-iterations',max_duration_seconds=config['max_duration_seconds']))
        offset+=count
    return templates,phases,offset


def submission_deadline(start, config):
    reserve=config['drain_seconds']+config.get('validation_seconds',config['drain_seconds'])+config.get('cleanup_seconds',30)
    return min(start+480,start+config['total_budget_seconds']-reserve)


def apply_phase_control(root, phase, config, deadline):
    delay=config.get('ai_ready_delay_ms',0) if phase['phase']=='measurement' and phase.get('variant')!='recovery' else 0
    atomic_json(root/'phase-control.json',dict(phase=phase['name'],ai_ready_delay_ms=delay),private=True)
    while time.monotonic()<deadline:
        ack=root/'phase-applied.json'
        if ack.exists() and json.loads(ack.read_text()).get('phase')==phase['name']:return
        time.sleep(.1)
    raise TimeoutError('phase readiness control acknowledgement')


def main(config_path):
    start=time.monotonic()
    config=validate_config(json.loads(Path(config_path).read_text()))
    for old_run in (HERE/'runtime').glob('*/manifest.json'):
        previous_run=json.loads(old_run.read_text())
        if previous_run.get('drain',{}).get('idle') is False or previous_run.get('drain_error') or previous_run.get('cleanup_pending'):
            raise RuntimeError('Previous run did not drain; inspect before new load: '+str(old_run.parent))
    run_id=datetime.now(ZoneInfo('Asia/Seoul')).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
    root=HERE/'runtime'/run_id;root.mkdir(parents=True,mode=0o700);(root/'commands').mkdir()
    atomic_json(root/'config.json',config,private=True)
    templates,phases,total=plan_phases(config,root)
    atomic_json(root/'templates.json',templates,private=True)
    server_config=dict(config,run_id=run_id,fixture_counts=[sum(b['photo_count'] for b in templates[p['variant']]) for p in phases for _ in range(p['count'])])
    atomic_json(root/'server-config.json',server_config,private=True)
    env=dict(os.environ,DERIVATIVE_RUN_DIR=str(root),DERIVATIVE_COMMAND_TIMEOUT=str(config.get('command_seconds',120)))
    compose=['docker','compose','-p','yeodam-performance','-f','docker-compose.performance.yml','-f','docker-compose.derivative.yml']
    deadline=start+config['total_budget_seconds'];load_deadline=submission_deadline(start,config);bootstrap_deadline=min(deadline,start+config['bootstrap_seconds']); collector=None; server=None; k6=None; drained=False
    manifest=dict(run_id=run_id,scenario=config['scenario'],config={k:v for k,v in config.items() if k not in ('files','small_files')},
                  photos=[{k:v for k,v in f.items() if k!='path'} for f in config['files']],
                  small_photos=[{k:v for k,v in f.items() if k!='path'} for f in config.get('small_files',[])],
                  collector='Linux wait4 (reaped descendants; max RSS is maximum, not sum)',peak_scope='new container lifetime including startup',
                  batch_shapes={variant:[{k:b[k] for k in ('photo_count','bytes','sha256','complete')} for b in batches] for variant,batches in templates.items()},
                  generator_max_rss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*(1 if sys.platform=='darwin' else 1024),
                  host_cpu_count=os.cpu_count(),host_platform=sys.platform,private_runtime=str(root),phases=[])
    manifest['head']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    patch=subprocess.check_output(['git','diff','HEAD'],cwd=ROOT);manifest['patch_sha256']=hashlib.sha256(patch).hexdigest()
    (root/'worktree.patch').write_bytes(patch)
    sources=['build.gradle','docker-compose.derivative.yml']+[str(p.relative_to(ROOT)) for p in HERE.glob('*.py')]+[str(HERE.relative_to(ROOT)/'worker.js'),str(HERE.relative_to(ROOT)/'Dockerfile')]+[
        'src/main/java/com/yeodam/yeodambe/trip/service/TripAttachmentDerivativeService.java',
        'src/main/java/com/yeodam/yeodambe/trip/service/TripDerivativeScheduler.java',
        'src/main/resources/application.yaml',
        'src/main/java/com/yeodam/yeodambe/trip/service/TripAttachmentService.java',
        'src/main/java/com/yeodam/yeodambe/integration/service/TripPhotoAnalysisService.java',
        'src/performanceTest/java/com/yeodam/yeodambe/trip/performance/StageMeasurements.java',
        'src/performanceTest/java/com/yeodam/yeodambe/trip/performance/LocalAnalysisStub.java',
        'src/performanceTest/java/com/yeodam/yeodambe/trip/performance/DerivativeK6PipelineTest.java',
        'src/main/java/com/yeodam/yeodambe/common/logging/BackendJsonLogFormatter.java',
        'src/performanceTest/java/com/yeodam/yeodambe/trip/performance/DerivativeK6ServerTest.java',
        'src/performanceTest/java/com/yeodam/yeodambe/trip/performance/PostprocessLoadFixture.java']
    manifest['source_hashes']={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in sources}
    for name in sources:
        copy=root/'source-snapshot'/name;copy.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,copy)
    target=ROOT/'performance/targets/backend.json'; previous=target.read_bytes() if target.exists() else None
    def interrupted(sig,frame):raise KeyboardInterrupt
    old={sig:signal.signal(sig,interrupted) for sig in (signal.SIGINT,signal.SIGTERM)}
    def command(args,timeout=30,**kwargs):
        remaining=deadline-time.monotonic()
        if remaining<=0:raise TimeoutError('total deadline exceeded before command')
        return subprocess.run(args,cwd=ROOT,env=env,timeout=min(timeout,remaining),check=True,**kwargs)
    manifest.update(purpose=config.get('purpose','legacy'),scenario_plan=config.get('scenario_plan','legacy'),scenario_id=config.get('scenario_id',config['scenario']),baseline_run_id=config.get('baseline_run_id'),load_cutoff_seconds=load_deadline-start,cleanup_pending=True)
    atomic_json(root/'manifest.json',manifest)
    try:
        info=command(['docker','info','--format','{{json .}}'],capture_output=True,text=True)
        docker_info=json.loads(info.stdout);manifest['docker_vm']={k:docker_info.get(k) for k in ('NCPU','MemTotal','Architecture','KernelVersion')}
        with (root/'build.log').open('w') as build_log:
            command(compose+['build','derivative'],timeout=max(.1,bootstrap_deadline-time.monotonic()),stdout=build_log,stderr=subprocess.STDOUT)
        command(compose+['up','-d','--no-deps','--force-recreate','derivative'],timeout=max(.1,bootstrap_deadline-time.monotonic()))
        container=command(compose+['ps','-q','derivative'],capture_output=True,text=True).stdout.strip()
        manifest['container_id']=container
        server=open(root/'server.log','w')
        logs=subprocess.Popen(['docker','logs','-f',container],cwd=ROOT,stdout=server,stderr=subprocess.STDOUT)
        collector_log=(root/'collector.log').open('w')
        collector=subprocess.Popen(['docker','exec',container,'python3','performance/derivative/run.py','--collect','/run-data'],cwd=ROOT,stdout=collector_log,stderr=subprocess.STDOUT)
        ready=wait_file(root/'ready.json',bootstrap_deadline)
        manifest['ready']=ready
        manifest['container_limits']=command(['docker','exec',container,'sh','-c','cat /sys/fs/cgroup/cpu.max; cat /sys/fs/cgroup/memory.max'],capture_output=True,text=True).stdout
        manifest['tools']=command(['docker','exec',container,'sh','-c','java -version 2>&1; convert -version; exiftool -ver; cat /etc/ImageMagick-6/policy.xml'],capture_output=True,text=True).stdout
        # Server paths live in the shared volume; bodies are opened by host k6.
        fixtures=json.loads((root/'fixtures.json').read_text())
        if len(fixtures)!=total:raise RuntimeError('fixture count mismatch')
        for f in fixtures:
            token=f['cookie'].split('accessToken=',1)[1].split(';',1)[0]
            import base64
            payload=token.split('.')[1];claim=json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))
            if claim['exp']-time.time()<deadline-time.monotonic():raise RuntimeError('actual JWT expires before run budget')
        index=0
        while index<len(phases):
            phase=phases[index]; group=[phase]
            if config['scenario']=='S5' and phase['name'].startswith('large'):
                group=[p for p in phases[index:] if p.get('schedule')==phase['schedule']]
                index+=len(group)
            else:index+=1
            if time.monotonic()+config['drain_seconds']>=load_deadline:raise TimeoutError('remaining submission budget insufficient')
            if config.get('scenario_plan')=='2026-10-02':apply_phase_control(root,phase,config,min(load_deadline,time.monotonic()+10))
            phase_path=root/('phase-'+phase['name']+'.json')
            atomic_json(phase_path,dict(config,run_id=run_id,templates=templates,phases=group,base_url='http://127.0.0.1:18080/api',summary_path=str(root/('summary-'+phase['name']+'.json'))),private=True)
            phase_env=dict(env,RUN_CONFIG=str(phase_path),FIXTURES_FILE=str(root/'fixtures.json'),K6_PROMETHEUS_RW_SERVER_URL='http://127.0.0.1:19090/api/v1/write')
            phase_start=time.time(); output=open(root/('k6-'+phase['name']+'.log'),'w')
            raw=root/('raw-'+phase['name']+'.json')
            k6=subprocess.Popen(['bash','monitoring/run-k6.sh','--log-format=json','--log-output','file='+str(root/('client-'+phase['name']+'.jsonl')),'--out','json='+str(raw),str(HERE/'worker.js')],cwd=ROOT,env=phase_env,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
            max_seconds=max(p.get('start_seconds',0)+p.get('duration',p.get('max_duration_seconds',0)) for p in group)+config['graceful_seconds']+5
            phase_deadline=min(time.monotonic()+max_seconds,load_deadline)
            while k6.poll() is None and time.monotonic()<phase_deadline:
                if collector.poll() is not None:raise RuntimeError('resource collector stopped during measurement')
                if (root/'resources.csv').exists():
                    with (root/'resources.csv').open() as stream: last_rows=list(csv.DictReader(stream))
                    if last_rows and (float(last_rows[-1]['tmp_used_percent'])>=90 or int(last_rows[-1].get('oom_kill') or 0)>0):
                        raise RuntimeError('OOM/disk stop during measurement')
                time.sleep(.2)
            if k6.poll() is None:
                k6.send_signal(signal.SIGINT)
                try:k6.wait(timeout=5)
                except subprocess.TimeoutExpired:k6.kill();k6.wait()
                manifest['scenario_limit_reached']=True
            code=k6.returncode
            output.close(); k6=None
            manifest['phases'].append(dict(name=phase['name'],phase=phase['phase'],effective_workload={k:v for k,v in phase.items() if k in ('executor','rate','time_unit_seconds','duration','max_duration_seconds','vus')},start_epoch_ms=int(phase_start*1000),end_epoch_ms=int(time.time()*1000),exit_code=code))
            with (root/'k6-raw.json').open('a') as combined,raw.open() as source:shutil.copyfileobj(source,combined)
            limit=min(deadline,time.monotonic()+config['drain_seconds'])
            while time.monotonic()<limit:
                idle=json.loads((root/'idle.json').read_text()) if (root/'idle.json').exists() else {}
                if idle.get('epoch_ms',0)>time.time()*1000-1000 and idle.get('active_uploads')==0 and idle.get('active_batches')==0 and idle.get('queue_size')==0:
                    time.sleep(1);break
                time.sleep(.2)
            else:raise TimeoutError('phase drain exceeded')
            resources=list(csv.DictReader((root/'resources.csv').open()))
            if resources and (float(resources[-1]['tmp_used_percent'])>=90 or int(resources[-1].get('oom_kill') or 0)>0):raise RuntimeError('OOM/disk stop')
            manifest['phases'][-1]['drain_end_epoch_ms']=int(time.time()*1000)
            if code:raise RuntimeError('k6 failed; preserve evidence')
        atomic_json(root/'stop.json',dict(reason='normal',epoch_ms=int(time.time()*1000)))
        drain=wait_file(root/'drain.json',min(deadline,time.monotonic()+config.get('validation_seconds',config['drain_seconds'])))
        drained=drain.get('idle',False);manifest['drain']=drain
    except (Exception,KeyboardInterrupt) as error:
        manifest['error']=type(error).__name__+': '+str(error)
        if k6 and k6.poll() is None:
            k6.send_signal(signal.SIGINT)
            try:k6.wait(timeout=5)
            except subprocess.TimeoutExpired:k6.kill();k6.wait()
        atomic_json(root/'stop.json',dict(reason=type(error).__name__,epoch_ms=int(time.time()*1000)))
        try:
            drain=wait_file(root/'drain.json',min(deadline,time.monotonic()+config['drain_seconds']))
            drained=drain.get('idle',False);manifest['drain']=drain
        except Exception as drain_error:manifest['drain_error']=str(drain_error)
    finally:
        atomic_json(root/'collector-stop.json',{})
        if collector:
            try:collector.wait(timeout=5)
            except subprocess.TimeoutExpired:collector.terminate();collector.wait(timeout=5)
        with (root/'k6-raw.json').open('w') as combined:
            for raw_path in sorted(root.glob('raw-*.json')):
                with raw_path.open() as source:shutil.copyfileobj(source,combined)
        client_parse_errors=[]
        with (root/'client-events.jsonl').open('w') as combined:
            for client_path in sorted(root.glob('client-*.jsonl')):
                if client_path.name=='client-events.jsonl':continue
                for logged in read_lines(client_path,client_parse_errors):
                    try:event=json.loads(logged.get('msg','{}'))
                    except json.JSONDecodeError:continue
                    if event.get('event') in ('client_start','client_outcome'):combined.write(json.dumps(event)+'\n')
        atomic_json(root/'phases.json',manifest['phases'])
        try:
            query='{namespace="performance",application="yeodam-be",__name__=~"jvm_.*|process_.*|hikaricp_.*|yeodam_image_worker_.*"}'
            request='http://127.0.0.1:19090/api/v1/query_range?'+urlencode(dict(query=query,start=time.time()-(time.monotonic()-start),end=time.time(),step=5))
            with urlopen(request,timeout=5) as response:atomic_json(root/'prometheus.json',json.load(response))
        except Exception as error:manifest['prometheus_export_unavailable']=type(error).__name__
        result=summarize(root)
        manifest['elapsed_seconds']=time.monotonic()-start
        if client_parse_errors:result['invalid_reasons'].append('client_raw_json_corrupt')
        if manifest.get('scenario_limit_reached'):result['invalid_reasons'].append('measurement_window_interrupted')
        manifest['observed_input']=result.get('input_and_drain')
        if config.get('arrival_steps'):
            for phase in phases:
                stats=result['phase_stats'].get(phase['name'],{})
                if phase['phase']=='measurement' and (stats.get('queue_max',0)>=2 or stats.get('rejected_batches',0)>0):
                    manifest['bottleneck_rate']={key:phase[key] for key in ('rate','time_unit_seconds')}
                    manifest['bottleneck_selection_reason']='First observed full queue or rejected submission; capacity remains unverified'
                    break
        if config.get('bottleneck_rate'):manifest['bottleneck_rate']=config['bottleneck_rate']
        if config.get('baseline_path'):
            baseline=json.loads((Path(config['baseline_path'])/'manifest.json').read_text())
            manifest['comparison']=compare_runs(manifest,baseline)
            if not manifest['comparison']['valid']:result['invalid_reasons'].append('comparison_invalid')
        manifest['valid']=assess_run(config,manifest,result)
        atomic_json(root/'results.json',result)
        with (root/'results.csv').open('w') as stream:
            writer=csv.writer(stream);writer.writerow(['metric','value'])
            for key,value in result.items():writer.writerow([key,json.dumps(value)])
        if drained and manifest.get('drain',{}).get('valid',False):
            try:
                if time.monotonic()>=deadline:raise TimeoutError('cleanup deadline exceeded')
                command(compose+['stop','derivative'],timeout=config.get('cleanup_seconds',30))
                manifest['cleanup_pending']=False
            except (subprocess.SubprocessError,TimeoutError) as error:
                manifest['cleanup_error']=type(error).__name__
        manifest['elapsed_seconds']=time.monotonic()-start
        if manifest['elapsed_seconds']>config['total_budget_seconds'] or manifest['cleanup_pending']:
            manifest['valid']=False;manifest['cleanup_pending']=True
        atomic_json(root/'manifest.json',manifest)
        if 'logs' in locals():logs.terminate();logs.wait(timeout=5)
        if server:server.close()
        if 'collector_log' in locals():collector_log.close()
        if previous is None:target.unlink(missing_ok=True)
        else:target.write_bytes(previous)
        for sig,handler in old.items():signal.signal(sig,handler)
        export=ROOT.parent/'performance-results'/'derivative'/datetime.now(ZoneInfo('Asia/Seoul')).strftime('%Y-%m-%d')/run_id
        export.mkdir(parents=True,exist_ok=False)
        for name in ('manifest.json','results.json','results.csv','validation.json','client-events.jsonl','phases.json','worker-events.jsonl','resources.csv','k6-raw.json','prometheus.json','worktree.patch'):
            if (root/name).exists():shutil.copy2(root/name,export/name)
        shutil.copytree(root/'commands',export/'commands')
        shutil.copytree(root/'source-snapshot',export/'source-snapshot')
        for path in root.glob('summary-*.json'):shutil.copy2(path,export/path.name)
        manifest['elapsed_seconds']=time.monotonic()-start
        if manifest['elapsed_seconds']>config['total_budget_seconds']:
            manifest.update(valid=False,cleanup_pending=True,error='total deadline exceeded during export')
        atomic_json(root/'manifest.json',manifest);atomic_json(export/'manifest.json',manifest)
        print(json.dumps(dict(result=str(export),valid=manifest['valid'],error=manifest.get('error')),ensure_ascii=False))
    return 0 if manifest['valid'] else 1


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config');parser.add_argument('--collect');parser.add_argument('--summarize')
    args=parser.parse_args()
    if args.collect:collect_resources(args.collect)
    elif args.summarize:print(json.dumps(summarize(Path(args.summarize))))
    elif args.config:sys.exit(main(args.config))
    else:parser.error('--config is required')
