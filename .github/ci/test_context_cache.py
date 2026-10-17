import json
from pathlib import Path
import tempfile
import unittest

from context_cache import COMPONENTS, analyze, attach_timings


class ContextAnalysisTest(unittest.TestCase):
    def rows(self):
        components = {name: 1 for name in COMPONENTS}
        return [
            {
                "event": "key",
                "key": 1,
                "components": components,
                "classes": ["example.Config"],
                "customizers": [],
            },
            {"event": "discovered", "cache": 1, "key": 1, "generation": 1},
            {
                "event": "snapshot",
                "cache": 1,
                "key": 1,
                "testClass": "example.A",
                "size": 1,
                "resident": [1],
                "hits": 2,
                "misses": 1,
            },
            {
                "event": "snapshot",
                "cache": 1,
                "key": 1,
                "testClass": "example.B",
                "size": 1,
                "resident": [1],
                "hits": 5,
                "misses": 1,
            },
        ]

    def analyze(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            for i, row in enumerate(rows, 1):
                row.update(sequence=i, springVersion="7.0.9", worker="1")
            (p / "context-events-1.jsonl").write_text(
                "".join(json.dumps(r) + "\n" for r in rows)
            )
            return analyze(p)

    def test_equal_key_reuse_is_not_recreation_or_cumulative_misses(self):
        result = self.analyze(self.rows())
        self.assertTrue(result["complete"])
        self.assertEqual(
            (result["loadedKeys"], result["contextsDiscovered"], result["recreations"]),
            (1, 1, 0),
        )
        self.assertEqual(result["nativeMissesAtSnapshots"], 1)
        self.assertEqual(result["keys"][0]["testClasses"], ["example.A", "example.B"])

    def test_lru_and_dirty_reloads_need_actual_preceding_close(self):
        rows = self.rows()
        for generation, reason in [(2, "lru"), (3, "dirties")]:
            rows += [
                {
                    "event": "closed",
                    "cache": 1,
                    "key": 1,
                    "generation": generation - 1,
                    "reason": reason,
                },
                {"event": "discovered", "cache": 1, "key": 1, "generation": generation},
                {
                    "event": "snapshot",
                    "cache": 1,
                    "key": 1,
                    "testClass": "example.C",
                    "size": 1,
                    "resident": [1],
                    "hits": 9,
                    "misses": generation,
                },
            ]
        result = self.analyze(rows)
        self.assertTrue(result["complete"])
        self.assertEqual(result["reloadReasons"], {"lru": 1, "dirties": 1})
        self.assertEqual(result["recreations"], 2)

    def test_different_property_component_is_distinct_key_not_reload(self):
        rows = self.rows()
        components = dict(rows[0]["components"], properties=2)
        rows += [
            {
                "event": "key",
                "key": 2,
                "components": components,
                "classes": ["example.Config"],
                "customizers": [],
            },
            {"event": "discovered", "cache": 1, "key": 2, "generation": 1},
            {
                "event": "snapshot",
                "cache": 1,
                "key": 2,
                "testClass": "example.C",
                "size": 2,
                "resident": [1, 2],
                "hits": 9,
                "misses": 2,
            },
        ]
        result = self.analyze(rows)
        self.assertTrue(result["complete"])
        self.assertEqual((result["loadedKeys"], result["recreations"]), (2, 0))
        self.assertEqual(
            result["nearestRelatedKeys"][0]["differentComponents"], ["properties"]
        )

    def test_missing_discovery_is_incomplete_not_zero_reloads(self):
        rows = self.rows()
        rows[-1]["misses"] = 2
        self.assertFalse(self.analyze(rows)["complete"])

    def test_native_log_disagreement_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            rows = self.rows()
            for i, row in enumerate(rows, 1):
                row.update(sequence=i, springVersion="7.0.9", worker="1")
            (p / "context-events-1.jsonl").write_text(
                "".join(json.dumps(r) + "\n" for r in rows)
            )
            self.assertFalse(analyze(p, expected_misses=2)["complete"])

    def test_reload_without_close_is_not_called_lru(self):
        rows = self.rows() + [
            {"event": "discovered", "cache": 1, "key": 1, "generation": 2},
            {
                "event": "snapshot",
                "cache": 1,
                "key": 1,
                "testClass": "example.C",
                "size": 1,
                "resident": [1],
                "hits": 9,
                "misses": 2,
            },
        ]
        result = self.analyze(rows)
        self.assertFalse(result["complete"])
        self.assertEqual(result["reloadReasons"], {"unobserved": 1})

    def test_observer_error_and_unknown_spring_version_fail_report(self):
        rows = self.rows() + [{"event": "error", "errorType": "example.Failure"}]
        self.assertFalse(self.analyze(rows)["complete"])
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            (p / "context-events-1.jsonl").write_text(
                '{"sequence":1,"springVersion":"8.0","worker":"1","event":"key"}\n'
            )
            self.assertFalse(analyze(p)["complete"])

    def test_startup_join_keeps_class_wall_separate(self):
        result = self.analyze(self.rows())
        attach_timings(
            result,
            [{"className": "example.A", "elapsedSeconds": 12}],
            [
                {"sourceTestClass": "example.A", "seconds": 2},
                {"sourceTestClass": "example.Unmapped", "seconds": 3},
            ],
        )
        key = result["keys"][0]
        self.assertEqual(key["classWallObservations"][0]["seconds"], 12)
        self.assertEqual(key["springStartupObservations"][0]["seconds"], 2)
        self.assertEqual(result["unmatchedSpringStartupObservations"], 1)


if __name__ == "__main__":
    unittest.main()
