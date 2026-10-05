#!/usr/bin/env python3
"""Compare preserved A/B results only when input conditions match."""
import argparse
import json
from pathlib import Path


def compare(baseline, candidate):
    for key in ['dataset', 'users', 'sessions', 'csrf', 'active_users', 'vus', 'read_rate', 'write_rate', 'heap', 'hikari_max', 'resources_scope', 'mysql_container', 'mysql_version', 'docker_vm', 'duration_seconds']:
        if baseline['manifest'].get(key) != candidate['manifest'].get(key):
            raise ValueError('Comparison condition differs: ' + key)
    a, b = baseline['reports'], candidate['reports']
    if not a or len(a) != len(b):
        raise ValueError('Missing or unequal repeat counts')
    result = []
    for one, two in zip(a, b):
        if not one['valid'] or not two['valid']:
            raise ValueError('Invalid load result cannot establish improvement')
        if one['scenario'] != two['scenario'] or one['requested_http_rps'] != two['requested_http_rps']:
            raise ValueError('Scenario/rate differs')
        if one['window_end_ms'] - one['window_start_ms'] != two['window_end_ms'] - two['window_start_ms']:
            raise ValueError('Measurement duration differs')
        metrics = {}
        for key in ['success_rps', 'success_p95_ms', 'client_success_p95_ms']:
            x, y = one[key], two[key]
            metrics[key] = dict(A=x, B=y, decrease_percent=(x - y) / x * 100 if x else None)
        for stage in ['session', 'member', 'csrf']:
            for key in ['stage_p95_ms', 'sql_per_request']:
                x = one['stages'].get(stage, {}).get(key)
                y = two['stages'].get(stage, {}).get(key)
                metrics[stage + '_' + key] = dict(A=x, B=y,
                    decrease_percent=(x - y) / x * 100 if x and y is not None else None)
        result.append(dict(scenario=one['scenario'], metrics=metrics,
                           A_resources=one.get('resources', {}), B_resources=two.get('resources', {})))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    args = parser.parse_args()
    def read(path):
        if (path / 'incomplete.json').exists() or not json.loads((path / 'validation.json').read_text()).get('idle'):
            raise ValueError('Server lifecycle incomplete')
        return dict(manifest=json.loads((path / 'manifest.json').read_text()),
                    reports=json.loads((path / 'reports.json').read_text()))
    print(json.dumps(compare(read(args.baseline), read(args.candidate)), ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
