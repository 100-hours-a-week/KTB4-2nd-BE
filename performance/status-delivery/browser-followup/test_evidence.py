import hashlib
import json
import unittest
from pathlib import Path

from analyze import analyze

ROOT = Path(__file__).resolve().parent


class BrowserEvidenceTest(unittest.TestCase):
    def test_evidence_checksums(self):
        for name, digest in json.loads((ROOT / 'evidence/checksums.json').read_text()).items():
            self.assertEqual(hashlib.sha256((ROOT / 'evidence' / name).read_bytes()).hexdigest(), digest)

    def test_completed_cases_and_failure_injection(self):
        rows = {r['case']: r for r in analyze()}
        self.assertEqual(len(rows), 5)
        self.assertTrue(all(r['completed'] for r in rows.values()))
        self.assertEqual(rows['http-503']['http_503'], 5)
        self.assertEqual(rows['reload']['loads'], 2)
        for name in ['normal-1', 'normal-2', 'normal-3']:
            self.assertIsNotNone(rows[name]['transitions']['PROCESSING'])
            self.assertEqual(rows[name]['http_503'], 0)


if __name__ == '__main__':
    unittest.main()
