import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import test_timing as timing


class MeasurementTest(unittest.TestCase):
    def test_cumulative_context_counters_are_not_added(self):
        values = {
            "contextCaches": {},
            "containerStarts": [],
            "flyway": [],
            "springStarts": [],
        }
        log = "\n".join(
            [
                "DefaultContextCache@abc size = 2, maxSize = 8, parentContextCount = 0, hitCount = 20, missCount = 2",
                "DefaultContextCache@abc size = 1, maxSize = 8, parentContextCount = 0, hitCount = 23, missCount = 3",
                "Container mysql:9.7.2 started in PT4.25S",
                "Container apache/kafka:3.9.1 started in PT1M2.5S",
                "Successfully applied 25 migrations to schema secret (execution time 00:01.234s)",
                "Started ExampleTest in 2.5 seconds",
                "token=NEVER_PUBLISH_ME",
            ]
        )
        timing.collect_observations(log, values)
        self.assertEqual(values["contextCaches"]["unknown:abc"]["hits"], 23)
        self.assertEqual(values["contextCaches"]["unknown:abc"]["misses"], 3)
        self.assertEqual(values["containerStarts"][0]["seconds"], 4.25)
        self.assertEqual(values["containerStarts"][1]["seconds"], 62.5)
        self.assertEqual(values["flyway"][0]["seconds"], 1.234)
        self.assertNotIn("secret", json.dumps(values))
        self.assertNotIn("NEVER_PUBLISH_ME", json.dumps(values))

    def test_missing_and_truncated_reports_are_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertFalse(timing.summarize(root)["complete"])
            xml = root / "test-results/test/TEST-broken.xml"
            xml.parent.mkdir(parents=True)
            xml.write_text("<testsuite")
            data = timing.summarize(root)
            self.assertFalse(data["complete"])
            self.assertEqual(len(data["errors"]), 1)

    def test_failures_remain_visible_without_exception_or_parameter_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            xml = root / "test-results/test/TEST-example.Test.xml"
            xml.parent.mkdir(parents=True)
            xml.write_text("""<testsuite name="example.Test" tests="2" failures="1" errors="0" skipped="0" time="3.5">
<testcase name="method(SECRET_TOKEN)" time="2"><failure>PRIVATE_EXCEPTION</failure></testcase>
<testcase name="other" time="1.5"/>
<system-out>Authorization: SECRET_TOKEN</system-out></testsuite>""")
            output = root / "ci-timing/test-classes.jsonl"
            output.parent.mkdir(parents=True)
            output.write_text(
                json.dumps(
                    {
                        "className": "example.Test",
                        "elapsedSeconds": 4,
                        "testsIncludingNested": 2,
                        "failuresIncludingNested": 1,
                    }
                )
                + "\n"
            )
            profile = root / "reports/profile/profile-test.html"
            profile.parent.mkdir(parents=True)
            profile.write_text("<html></html>")
            data = timing.summarize(root)
            self.assertTrue(data["complete"])
            self.assertEqual(data["totals"]["tests"], 2)
            self.assertEqual(data["totals"]["failures"], 1)
            self.assertEqual(data["testcaseSecondsSum"], 3.5)
            self.assertNotIn("SECRET_TOKEN", json.dumps(data))
            self.assertNotIn("PRIVATE_EXCEPTION", json.dumps(data))

    def test_gradle_failure_exit_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with (
                patch.object(timing, "OUTPUT", output),
                patch.object(timing, "ResourceMonitor"),
                patch.object(timing, "save") as save,
                patch.object(
                    timing.subprocess, "check_output", return_value="checkout\n"
                ),
                patch.object(
                    timing.subprocess,
                    "run",
                    side_effect=[
                        SimpleNamespace(stdout="", stderr="java 25"),
                        SimpleNamespace(returncode=7),
                    ],
                ),
            ):
                self.assertEqual(timing.run(), 7)
                self.assertEqual(save.call_args.args[1]["exitCode"], 7)


if __name__ == "__main__":
    unittest.main()
