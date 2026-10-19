import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import resource_usage as usage
import test_timing as timing


class ResourceTest(unittest.TestCase):
    def test_cpu_excludes_guest_double_count(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc / "stat").write_text("cpu 100 0 20 200 30 0 0 10 80 0\n")
            (proc / "meminfo").write_text(
                "MemTotal: 1000 kB\nMemAvailable: 600 kB\nSwapTotal: 100 kB\nSwapFree: 80 kB\n"
            )
            (proc / "vmstat").write_text("oom_kill 2\n")
            row = usage.host_snapshot(proc)
            self.assertEqual(row["cpuTicks"], 360)
            self.assertEqual(row["idleTicks"], 230)
            self.assertEqual(row["swapUsedKiB"], 20)
            self.assertEqual(
                usage.cpu_percent(
                    {"cpuTicks": 100, "idleTicks": 50},
                    {"cpuTicks": 200, "idleTicks": 75},
                ),
                75,
            )
            self.assertIsNone(usage.cpu_percent(row, row))

    def test_sampled_views_not_summed_and_missing_is_incomplete(self):
        host = {
            "cpuTicks": 100,
            "idleTicks": 50,
            "memTotalKiB": 1000,
            "memAvailableKiB": 600,
            "swapUsedKiB": 0,
            "oomKills": 0,
        }
        samples = [
            {
                "elapsedSeconds": 0,
                "host": host,
                "hostCpuPercent": None,
                "workers": [{"pid": 1, "startTicks": 2, "cpuTicks": 3, "rssKiB": 123}],
                "containers": [
                    {
                        "id": "id",
                        "image": "mysql:9.7.2",
                        "running": True,
                        "cpuPercentOfOneCore": 150,
                        "memoryWorkingSetBytes": 100,
                    }
                ],
            }
        ]
        samples.append(
            {
                "elapsedSeconds": 5,
                "host": {**host, "cpuTicks": 200, "idleTicks": 75},
                "hostCpuPercent": 75,
            }
        )
        result = usage.aggregate(samples, [], 5)
        self.assertTrue(result["complete"])
        self.assertEqual(result["hostSampledPeakUsedKiB"], 400)
        self.assertEqual(result["hostCpuMeanPercent"], 75)
        self.assertEqual(result["workerProcessesObserved"], 1)
        self.assertEqual(result["containersObserved"], 1)
        self.assertFalse(usage.aggregate(samples, [{"kind": "error"}], 5)["complete"])
        self.assertFalse(usage.aggregate([], [], 0)["complete"])

    def test_worker_parser_keeps_no_command_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            process = proc / "123"
            process.mkdir()
            (process / "cmdline").write_bytes(
                b"java\0worker.org.gradle.process.internal.worker.GradleWorkerMain\0Gradle Test Executor 1\0PRIVATE_TOKEN"
            )
            fields = ["S"] + ["0"] * 21
            fields[11] = "20"
            fields[12] = "3"
            fields[19] = "999"
            (process / "stat").write_text("123 (java worker) " + " ".join(fields))
            (process / "status").write_text("Name:\tjava\nVmRSS:\t1000 kB\n")
            rows = usage.worker_snapshot(proc)
            self.assertEqual(
                rows[0], {"pid": 123, "startTicks": 999, "cpuTicks": 23, "rssKiB": 1000}
            )
            self.assertNotIn("PRIVATE_TOKEN", json.dumps(rows))

    def test_memory_units_and_missing_values(self):
        self.assertEqual(usage.memory_bytes("1.5MiB"), 1572864)
        self.assertEqual(usage.memory_bytes("2 MB"), 2000000)
        self.assertIsNone(usage.memory_bytes("--"))

    def test_docker_parser_preserves_cpu_units_and_ignores_other_fields(self):
        inventory = json.dumps(
            {
                "ID": "a",
                "Image": "redis:7.4-alpine",
                "State": "running",
                "Names": "do-not-publish",
            }
        )
        stats = json.dumps(
            {
                "ID": "a",
                "CPUPerc": "125.5%",
                "MemUsage": "1MiB / 16GiB",
                "Other": "PRIVATE",
            }
        )
        with patch.object(
            usage.subprocess,
            "run",
            side_effect=[
                type(
                    "Result", (), {"stdout": inventory, "returncode": 0, "stderr": ""}
                )(),
                type("Result", (), {"stdout": stats, "returncode": 0, "stderr": ""})(),
            ],
        ):
            rows = usage.docker_snapshot()
        self.assertEqual(rows[0]["cpuPercentOfOneCore"], 125.5)
        self.assertEqual(rows[0]["memoryWorkingSetBytes"], 1048576)
        self.assertNotIn("PRIVATE", json.dumps(rows))
        self.assertNotIn("do-not-publish", json.dumps(rows))

    def test_monitor_raw_samples_reproduce_final_host_summary(self):
        counter = [0]

        def snapshot():
            counter[0] += 100
            return {
                "cpuTicks": counter[0],
                "idleTicks": counter[0] // 2,
                "memTotalKiB": 1000,
                "memAvailableKiB": 500,
                "swapUsedKiB": 0,
                "oomKills": 0,
            }

        workers = [{"pid": 1, "startTicks": 2, "cpuTicks": 3, "rssKiB": 100}]
        containers = [
            {
                "id": "a",
                "image": "redis:7.4-alpine",
                "running": True,
                "cpuPercentOfOneCore": 1,
                "memoryWorkingSetBytes": 100,
            }
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with (
                patch.object(usage, "host_snapshot", side_effect=snapshot),
                patch.object(usage, "worker_snapshot", return_value=workers),
                patch.object(usage, "docker_snapshot", return_value=containers),
            ):
                monitor = usage.ResourceMonitor(path, interval=0.001)
                monitor.start()
                time.sleep(0.015)
                monitor.stop()
            raw = [
                json.loads(line)
                for line in (path / "resources.jsonl").read_text().splitlines()
            ]
            summary = json.loads((path / "resources-summary.json").read_text())
            self.assertTrue(summary["complete"])
            self.assertTrue(raw[-1]["finalHostSample"])
            self.assertEqual(summary["samples"], len(raw))
            self.assertEqual(
                summary["hostCpuMeanPercent"],
                usage.cpu_percent(raw[0]["host"], raw[-1]["host"]),
            )

    def test_context_cache_ids_are_separated_by_worker(self):
        values = {
            "contextCaches": {},
            "containerStarts": [],
            "flyway": [],
            "springStarts": [],
        }
        log = "DefaultContextCache@abc size = 1, maxSize = 8, hitCount = 20, missCount = 2"
        timing.collect_observations(log, values, "Gradle Test Executor 1")
        timing.collect_observations(log, values, "Gradle Test Executor 2")
        self.assertEqual(len(values["contextCaches"]), 2)


if __name__ == "__main__":
    unittest.main()
