import tempfile
import unittest
from unittest.mock import Mock, patch
from pathlib import Path

import report
import run


class TestEvidence(unittest.TestCase):
    def test_cleanup_selects_only_our_session_and_its_exact_ryuk_name(self):
        own = {"Id": "ours", "Labels": {run.LABEL: "our-session"}}
        other = {"Id": "other", "Labels": {run.LABEL: "other-session"}}
        reaper = {"Id": "ryuk", "Image": "testcontainers/ryuk:0.14.0",
                  "Labels": {"org.testcontainers": "true"}, "Names": ["/testcontainers-ryuk-our-session"]}
        misleading = {"Id": "background", "Image": "mysql:9.7.2",
                      "Labels": {}, "Names": ["/testcontainers-ryuk-our-session"]}
        self.assertEqual(run.owned_containers([own, other, reaper, misleading], "our-session"), [own, reaper])

    def test_background_event_detection_excludes_only_owned_resources(self):
        own = {"Actor": {"Attributes": {run.LABEL: "session"}}}
        other = {"Actor": {"Attributes": {run.LABEL: "another"}}}
        ryuk = {"Actor": {"Attributes": {"image": "testcontainers/ryuk:0.14.0",
                "org.testcontainers": "true", "name": "testcontainers-ryuk-session"}}}
        self.assertTrue(run.event_owned(own, "session"))
        self.assertTrue(run.event_owned(ryuk, "session"))
        self.assertFalse(run.event_owned(other, "session"))
        self.assertFalse(run.event_owned(own, None))

    def test_cleanup_accepts_ryuk_removal_race_and_reports_real_failure(self):
        own = {"Id": "ours", "Labels": {run.LABEL: "session"}}
        for after, expected_errors in (([], 0), ([own], 1)):
            with self.subTest(after=after), tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                (path / "events.tsv").write_text("1\t1\tjvm_start\tsession\t1\n")
                docker = Mock()
                docker.containers.side_effect = [[own], after]
                errors = []
                with patch.object(run.subprocess, "run", return_value=Mock(returncode=1)):
                    run.cleanup_session(docker, path, errors)
                self.assertEqual(len(errors), expected_errors)

    def test_different_test_lists_are_not_comparable(self):
        first = {"valid": True, "tests": [{"class": "One", "name": "test", "status": "passed"}]}
        second = {"valid": True, "tests": [{"class": "Two", "name": "test", "status": "passed"}]}
        self.assertFalse(report.comparable([first, second]))

    def test_skipped_test_does_not_count_as_success(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "TEST-fixture.xml").write_text(
                '<testsuite><testcase classname="One" name="skipped">'
                '<skipped message="Docker unavailable"/></testcase></testsuite>'
            )
            result = report.test_results(Path(directory))
        self.assertEqual(result[0]["status"], "skipped")
        self.assertEqual(result[0]["reason"], "Docker unavailable")

    def test_memory_peak_uses_simultaneous_sum_not_sum_of_individual_peaks(self):
        samples = [
            {"containers": [{"id": "one", "memory_bytes": 100}, {"id": "two", "memory_bytes": 10}]},
            {"containers": [{"id": "one", "memory_bytes": 20}, {"id": "two", "memory_bytes": 90}]},
        ]
        self.assertEqual(report.memory_summary(samples)["peak_bytes"], 110)
        self.assertEqual(report.memory_summary(samples)["mean_bytes"], 110)

    def test_failed_or_empty_runs_are_not_comparable(self):
        self.assertFalse(report.comparable([{"valid": True, "tests": []}]))
        self.assertFalse(report.comparable([{"valid": False, "tests": [{"class": "One", "name": "test"}]}]))

    def test_recreated_context_and_simultaneous_lifetime_are_counted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "events.tsv")
            path.write_text(
                "1000\t1\tjvm_start\tsession\t900\n"
                "1100\t1\tcontext_refresh\tfirst\tconfig-a\n"
                "1200\t1\tcontext_refresh\tsecond\tconfig-b\n"
                "1300\t1\tcontext_close\tfirst\t\n"
                "1400\t1\tcontext_refresh\tthird\tconfig-a\n"
                "1500\t1\tcontext_close\tsecond\t\n"
                "1600\t1\tcontext_close\tthird\t\n"
                "1900\t1\tjvm_end\t\t\n"
            )
            result = report.context_summary(path)
        self.assertEqual(result["created"], 3)
        self.assertEqual(result["peak_active"], 2)
        self.assertEqual(result["recreated"], 1)
        self.assertEqual(result["jvm_start_to_shutdown_hook_seconds"], 1)

    def test_same_test_inventory_is_comparable_despite_different_execution_order(self):
        first = {"class": "One", "name": "first", "status": "passed"}
        second = {"class": "Two", "name": "second", "status": "passed"}
        self.assertTrue(report.comparable([{"valid": True, "tests": [first, second]},
                                           {"valid": True, "tests": [second, first]}]))


if __name__ == "__main__":
    unittest.main()
