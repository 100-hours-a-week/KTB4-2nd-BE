import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_shards import aggregate, compare, partition


class ShardingTest(unittest.TestCase):
    def test_new_package_private_nested_and_anonymous_classes_are_partitioned_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("Slow", "Slow$Nested", "Slow$1", "PackagePrivate", "Other", "NewTest"):
                path = root / ("example/" + name + ".class")
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(name.encode())
            hints = {"contextGroups": [["example.Slow", "example.PackagePrivate"]],
                     "classSeconds": {"example.Slow": 10, "example.Other": 8}}
            a = partition([root], hints, 2)
            b = partition([root], hints, 2)
            self.assertEqual(a, b)
            self.assertEqual(set(a["owners"]), set(a["compiledClasses"]))
            self.assertEqual(a["owners"]["example.Slow"], a["owners"]["example.Slow$Nested"])
            self.assertEqual(a["owners"]["example.Slow"], a["owners"]["example.Slow$1"])
            self.assertEqual(a["owners"]["example.Slow"], a["owners"]["example.PackagePrivate"])
            self.assertNotEqual(a["owners"]["example.Slow"], a["owners"]["example.Other"])
            self.assertIn("example.NewTest", a["owners"])

    def test_missing_compiled_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                partition([Path(directory)], {"contextGroups": [], "classSeconds": {}}, 2)

    def reports(self, root):
        plan = {"runnerCount": 2, "compiledInventoryHash": "fixed", "hintsHash": "fixed",
                "owners": {"example.One": 1, "example.Two": 2}}
        for index, name in enumerate(("example.One", "example.Two"), 1):
            dest = root / str(index)
            dest.mkdir()
            data = {"complete": True, "totals": {"tests": 1, "failures": 0, "errors": 0, "skipped": index-1},
                    "junitClasses": [{"className": name}], "testIdentities": {name: 1}}
            command = {"checkoutSha": "same", "testShard": index, "testRunnerCount": 2,
                       "requestedTestForks": 1, "exitCode": 0}
            for file, value in (("summary.json", data), ("command.json", command), ("shard-plan.json", plan)):
                (dest / file).write_text(json.dumps(value))

    def test_skip_is_retained_and_aggregate_requires_every_shard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.reports(root)
            result = aggregate(root, 2)
            self.assertEqual(result["totals"]["tests"], 2)
            self.assertEqual(result["totals"]["skipped"], 1)
            (root / "2/summary.json").unlink()
            with self.assertRaises(ValueError):
                aggregate(root, 2)

    def test_failed_cancelled_duplicate_or_wrong_shard_does_not_pass(self):
        for mutation in ("failed", "incomplete", "duplicate", "wrong-owner", "identity-count", "inputs"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.reports(root)
                path = root / "2/summary.json"
                data = json.loads(path.read_text())
                if mutation == "failed":
                    data["totals"]["failures"] = 1
                elif mutation == "incomplete":
                    data["complete"] = False
                elif mutation == "duplicate":
                    data["junitClasses"][0]["className"] = "example.One"
                elif mutation == "wrong-owner":
                    data["junitClasses"][0]["className"] = "example.Unknown"
                elif mutation == "identity-count":
                    data["testIdentities"] = {}
                elif mutation == "inputs":
                    p = root / "2/shard-plan.json"
                    value = json.loads(p.read_text())
                    value["compiledInventoryHash"] = "changed"
                    p.write_text(json.dumps(value))
                path.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    aggregate(root, 2)

    def test_same_count_but_different_tests_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.reports(root)
            baseline = aggregate(root, 2)
            candidate = copy.deepcopy(baseline)
            self.assertTrue(compare(baseline, candidate)["equivalent"])
            candidate["testIdentities"] = {"replaced-test": 2}
            with self.assertRaises(ValueError):
                compare(baseline, candidate)


if __name__ == "__main__":
    unittest.main()
