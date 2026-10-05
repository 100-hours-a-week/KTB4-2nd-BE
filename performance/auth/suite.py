#!/usr/bin/env python3
"""Run the predeclared normal/db/RTR profile comparisons sequentially in ABBA order."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import datetime
import run
import compare_capacity


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rdb-distribution',type=Path,required=True)
    p.add_argument('--redis-distribution',type=Path,default=run.REPO/'build/auth-capacity')
    p.add_argument('--profiles',nargs='+',choices=['normal','db','rtr-burst'],default=['normal','db','rtr-burst'])
    p.add_argument('--rate',type=int,default=200)
    p.add_argument('--vus',type=int,default=1000)
    p.add_argument('--java-image',default='eclipse-temurin:25-jre-noble')
    args=p.parse_args()
    for path,store in [(args.rdb_distribution,'rdb'),(args.redis_distribution,'redis')]:
        if not (path/'implementation.json').exists() or json.loads((path/'implementation.json').read_text())['store']!=store:
            p.error('Build both matching distributions before suite')
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output=run.REPO.parent/'performance-results/auth-capacity'/f'{stamp}-ABBA-suite'
    output.mkdir(mode=0o700);records=[]
    for profile in args.profiles:
        paths=[]
        for variant in ['A','B','B','A']:
            distribution=args.rdb_distribution if variant=='A' else args.redis_distribution
            command=[sys.executable,str(Path(__file__).with_name('capacity.py')),'measure','--variant',variant,
                     '--profile',profile,'--rate',str(args.rate),'--vus',str(args.vus),'--distribution',str(distribution),
                     '--java-image',args.java_image]
            completed=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            (output/f'{profile}-{len(paths)+1}-{variant}.log').write_text(completed.stdout)
            print(completed.stdout,flush=True)
            match=[line[9:] for line in completed.stdout.splitlines() if line.startswith('Results: ')]
            record=dict(profile=profile,variant=variant,exit=completed.returncode,path=match[-1] if match else None)
            records.append(record);paths.append(record);run.save(output/'runs.json',records)
            if completed.returncode:raise RuntimeError('Suite stopped on incomplete/failing evidence; previous runs preserved')
        for index,(a,b) in enumerate([(paths[0],paths[1]),(paths[3],paths[2])]):
            run.save(output/f'{profile}-comparison-{index+1}.json',compare_capacity.compare(Path(a['path']),Path(b['path'])))
    print('Suite:',output)
if __name__=='__main__':main()
