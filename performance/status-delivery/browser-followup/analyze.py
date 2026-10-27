"""Recalculate the isolated browser pilot without treating it as a load benchmark."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def analyze():
    data = json.loads((ROOT / 'evidence/browser-results.json').read_text())
    results = []
    for name, case in data['cases'].items():
        transitions = {}
        for marker in case['markers']:
            observations = [o for o in case['observations']
                            if o.get('type') == 'dom' and o.get('status') == marker['phase']
                            and o['epochMs'] >= marker['earliestCommitMs']]
            if not observations:
                transitions[marker['phase']] = None
                continue
            observed = observations[0]
            received = [o for o in case['observations'] if o.get('type') == 'received'
                        and o.get('status') == marker['phase']
                        and marker['earliestCommitMs'] <= o['epochMs'] <= observed['epochMs']]
            transitions[marker['phase']] = {
                'commit_to_dom_ms': observed['epochMs'] - marker['latestCommitMs'],
                'commit_to_next_frame_ms': observed['nextFrameEpochMs'] - marker['latestCommitMs'],
                'commit_bracket_ms': marker['latestCommitMs'] - marker['earliestCommitMs'],
                'receipt_to_dom_ms': observed['epochMs'] - received[-1]['epochMs'] if received else None,
            }
        completion = transitions.get('COMPLETED')
        results.append({'case': name, 'requests': len(case['requests']),
                        'http_503': sum(r.get('status') == 503 for r in case['requests']),
                        'loads': case['loads'], 'transitions': transitions,
                        'mysql_select_delta': case['after']['db']['Com_select'] - case['before']['db']['Com_select'],
                        'completed': completion is not None})
    return results


if __name__ == '__main__':
    print(json.dumps(analyze(), indent=2))
