"""Linux CI sampling; host totals and per-process/container views must not be added."""

import json
import os
import re
import subprocess
import threading
import time
from pathlib import Path


def host_snapshot(proc=Path("/proc")):
    cpu = [
        int(value) for value in (proc / "stat").read_text().splitlines()[0].split()[1:9]
    ]
    memory = {
        key: int(value.split()[0])
        for key, value in (
            line.split(":", 1) for line in (proc / "meminfo").read_text().splitlines()
        )
    }
    vm = dict(line.split() for line in (proc / "vmstat").read_text().splitlines())
    return {
        "cpuTicks": sum(cpu),
        "idleTicks": cpu[3] + cpu[4],
        "memTotalKiB": memory["MemTotal"],
        "memAvailableKiB": memory["MemAvailable"],
        "swapUsedKiB": memory["SwapTotal"] - memory["SwapFree"],
        "oomKills": int(vm["oom_kill"]),
    }


def worker_snapshot(proc=Path("/proc")):
    found = []
    for path in proc.glob("[0-9]*"):
        try:
            command = (path / "cmdline").read_bytes()
            if (
                b"GradleWorkerMain" not in command
                or b"Gradle Test Executor" not in command
            ):
                continue
            text = (path / "stat").read_text()
            fields = text[text.rfind(")") + 2 :].split()
            status = dict(
                line.split(":", 1)
                for line in (path / "status").read_text().splitlines()
                if ":" in line
            )
            found.append(
                {
                    "pid": int(path.name),
                    "startTicks": int(fields[19]),
                    "cpuTicks": int(fields[11]) + int(fields[12]),
                    "rssKiB": int(status["VmRSS"].split()[0]),
                }
            )
        except (
            FileNotFoundError,
            ProcessLookupError,
            PermissionError,
            KeyError,
            ValueError,
            IndexError,
        ):
            continue
    return found


def cpu_percent(previous, current):
    total = current["cpuTicks"] - previous["cpuTicks"]
    idle = current["idleTicks"] - previous["idleTicks"]
    return 100 * (total - idle) / total if total > 0 else None


def memory_bytes(value):
    match = re.fullmatch(
        r"([\d.]+)\s*(B|kB|KB|MB|GB|TB|KiB|MiB|GiB|TiB)", value.strip()
    )
    if not match:
        return None
    number, unit = match.groups()
    scale = {
        "B": 1,
        "kB": 1000,
        "KB": 1000,
        "MB": 1000**2,
        "GB": 1000**3,
        "TB": 1000**4,
        "KiB": 1024,
        "MiB": 1024**2,
        "GiB": 1024**3,
        "TiB": 1024**4,
    }
    return int(float(number) * scale[unit])


def docker_snapshot():
    def command(*args):
        result = subprocess.run(
            ["docker", *args], capture_output=True, text=True, timeout=5, check=False
        )
        if result.returncode and not (
            args[0] == "stats" and "no such container" in result.stderr.lower()
        ):
            raise subprocess.CalledProcessError(result.returncode, "docker sampling")
        return result.stdout

    inventory = [
        json.loads(line)
        for line in command(
            "ps", "--all", "--no-trunc", "--format", "{{json .}}"
        ).splitlines()
    ]
    stats = [
        json.loads(line)
        for line in command(
            "stats", "--all", "--no-stream", "--no-trunc", "--format", "{{json .}}"
        ).splitlines()
    ]
    by_id = {row["ID"]: row for row in inventory}
    values = []
    for row in stats:
        identifier = row["ID"]
        info = by_id.get(identifier)
        if info is None:  # Container changed state during sampling.
            continue
        cpu = row["CPUPerc"].removesuffix("%")
        values.append(
            {
                "id": identifier,
                "image": info["Image"],
                "running": info["State"] == "running",
                "cpuPercentOfOneCore": float(cpu)
                if re.fullmatch(r"[\d.]+", cpu)
                else None,
                "memoryWorkingSetBytes": memory_bytes(row["MemUsage"].split("/")[0]),
            }
        )
    return values


def aggregate(samples, errors, elapsed):
    hosts = [row["host"] for row in samples if row.get("host")]
    cpus = [
        row["hostCpuPercent"]
        for row in samples
        if row.get("hostCpuPercent") is not None
    ]
    workers, containers = {}, {}
    for row in samples:
        for worker in row.get("workers", []):
            key = f"{worker['pid']}:{worker['startTicks']}"
            current = workers.setdefault(
                key, {"pid": worker["pid"], "sampledPeakRssKiB": 0}
            )
            current["sampledPeakRssKiB"] = max(
                current["sampledPeakRssKiB"], worker["rssKiB"]
            )
        for container in row.get("containers", []):
            current = containers.setdefault(
                container["id"],
                {
                    "image": container["image"],
                    "sampledPeakCpuPercentOfOneCore": None,
                    "sampledPeakMemoryWorkingSetBytes": None,
                },
            )
            for source, target in [
                ("cpuPercentOfOneCore", "sampledPeakCpuPercentOfOneCore"),
                ("memoryWorkingSetBytes", "sampledPeakMemoryWorkingSetBytes"),
            ]:
                if container[source] is not None:
                    current[target] = max(current[target] or 0, container[source])
    complete = len(hosts) >= 2 and bool(cpus and workers and containers) and not errors
    return {
        "complete": complete,
        "errors": errors,
        "elapsedSeconds": elapsed,
        "samples": len(samples),
        "clockTicksPerSecond": os.sysconf("SC_CLK_TCK"),
        "hostCpuMeanPercent": cpu_percent(hosts[0], hosts[-1])
        if len(hosts) >= 2
        else None,
        "hostCpuSampledPeakPercent": max(cpus) if cpus else None,
        "hostSampledPeakUsedKiB": max(
            row["memTotalKiB"] - row["memAvailableKiB"] for row in hosts
        )
        if hosts
        else None,
        "hostMinAvailableKiB": min(row["memAvailableKiB"] for row in hosts)
        if hosts
        else None,
        "hostPeakSwapUsedKiB": max(row["swapUsedKiB"] for row in hosts)
        if hosts
        else None,
        "hostOomKillDelta": hosts[-1]["oomKills"] - hosts[0]["oomKills"]
        if len(hosts) >= 2
        else None,
        "workerProcessesObserved": len(workers),
        "workerSampledPeakConcurrent": max(
            (len(row.get("workers", [])) for row in samples), default=0
        ),
        "workers": workers,
        "containersObserved": len(containers),
        "containerSampledPeakRunning": max(
            (sum(c["running"] for c in row.get("containers", [])) for row in samples),
            default=0,
        ),
        "containers": containers,
        "limits": [
            "Five-second snapshots can miss brief processes and true peaks.",
            "Host used memory is MemTotal minus MemAvailable; worker RSS and Docker working set are different views. Do not add them.",
            "Host CPU is 0-100% of all cores; Docker CPU is 100% per logical core.",
            "Resource monitoring includes its own overhead and must be identical in both modes.",
            "Container IDs/counts are observed; removed or short-lived containers may be missed.",
        ],
    }


class ResourceMonitor:
    def __init__(self, directory, interval=5.0):
        self.directory = directory
        self.interval = interval
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.samples, self.errors = [], []

    def start(self):
        self.started = time.monotonic()
        self.thread.start()

    def _sample(self):
        row = {
            "timestampMs": int(time.time() * 1000),
            "elapsedSeconds": time.monotonic() - self.started,
        }
        try:
            row["host"] = host_snapshot()
            row["workers"] = worker_snapshot()
            row["hostCpuPercent"] = (
                cpu_percent(self.samples[-1]["host"], row["host"])
                if self.samples and self.samples[-1].get("host")
                else None
            )
        except (OSError, KeyError, ValueError) as error:
            self.errors.append({"source": "proc", "kind": type(error).__name__})
        try:
            row["containers"] = docker_snapshot()
        except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
            self.errors.append({"source": "docker", "kind": type(error).__name__})
        self.samples.append(row)
        with (self.directory / "resources.jsonl").open("a") as stream:
            stream.write(json.dumps(row) + "\n")

    def _loop(self):
        while not self.stop_event.is_set():
            start = time.monotonic()
            self._sample()
            self.stop_event.wait(max(0, self.interval - (time.monotonic() - start)))

    def stop(self):
        start = time.monotonic()
        self.stop_event.set()
        self.thread.join()
        # Capture final host counters without a trailing Docker stats interval.
        try:
            row = host_snapshot()
            self.samples.append(
                {
                    "timestampMs": int(time.time() * 1000),
                    "finalHostSample": True,
                    "elapsedSeconds": time.monotonic() - self.started,
                    "host": row,
                    "hostCpuPercent": cpu_percent(self.samples[-1]["host"], row)
                    if self.samples and self.samples[-1].get("host")
                    else None,
                }
            )
        except (OSError, KeyError, ValueError) as error:
            self.errors.append({"source": "proc-final", "kind": type(error).__name__})
        if self.samples and self.samples[-1].get("finalHostSample"):
            with (self.directory / "resources.jsonl").open("a") as stream:
                stream.write(json.dumps(self.samples[-1]) + "\n")
        summary = aggregate(self.samples, self.errors, time.monotonic() - self.started)
        summary["finalizeSeconds"] = time.monotonic() - start
        summary["intervalSeconds"] = self.interval
        (self.directory / "resources-summary.json").write_text(
            json.dumps(summary, indent=2) + "\n"
        )
