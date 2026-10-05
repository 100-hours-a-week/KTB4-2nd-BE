#!/usr/bin/env python3
"""Compare equal-budget completed A/B runs; never turn verify into a capacity claim."""
import argparse
import json
from pathlib import Path
import capacity


def compare(a_path, b_path):
    manifests=[]; reports=[]; limits=[]
    for path in [a_path,b_path]:
        if not json.loads((path/'validation.json').read_text()).get('complete'):
            raise ValueError('Incomplete run')
        m=json.loads((path/'manifest.json').read_text())
        if m['mode'] in ['verify','fault']:raise ValueError('Verify is not a performance measurement')
        rows=json.loads((path/'reports.json').read_text())
        if not rows or any(not row['valid'] for row in rows):raise ValueError('Invalid evidence')
        if any(not row.get('stabilization',{}).get('passed') or not row.get('environment_complete') for row in rows):
            raise ValueError('Current comparison requires stabilized, continuous observations')
        manifests.append(m);reports.append(rows)
        limits.append(json.loads((path/'capacity.json').read_text()) if (path/'capacity.json').exists() else None)
    a,b=manifests
    capacity.validate_comparison(a,b)
    for key in ['users','sessions','vus','heap','hikari','harness_hashes','backend_image']:
        if a[key]!=b[key]:raise ValueError('Comparison differs: '+key)
    if [a['variant'],b['variant']]!=['A','B']:raise ValueError('Expected A then B')
    if a['limits']['mysql']['image']!=b['limits']['mysql']['image']:raise ValueError('MySQL image differs')
    if a['mode']!=b['mode']:raise ValueError('Measurement modes differ')
    if a['mode']=='measure' and (any(not row.get('coverage_complete') for rows in reports for row in rows)):
        raise ValueError('Full 10,000-user participation required')
    if a['mode']=='measure' and a['rate']!=b['rate']:raise ValueError('Common load differs')
    return dict(A_capacity=limits[0],B_capacity=limits[1],A_runs=reports[0],B_runs=reports[1],
                note='Equal system budget; MySQL allocation differs. Sequential comparison is not randomized causality proof.')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('A',type=Path);p.add_argument('B',type=Path)
    a=p.parse_args();print(json.dumps(compare(a.A,a.B),indent=2))
