#!/usr/bin/env python3
"""Run isolated backend test-environment comparisons and preserve raw evidence."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import csv
import datetime
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import signal
import socket
import statistics
import subprocess
import threading
import time
import urllib.parse
import urllib.error

import report

REPO = Path(__file__).resolve().parents[2]
TASKS = {"baseline": "testEnvironmentBaseline", "A": "testEnvironmentA",
         "B": "testEnvironmentB", "cache": "testEnvironmentCache"}
LABEL = "org.testcontainers.sessionId"


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def command(arguments):
    return subprocess.check_output(arguments, cwd=REPO, text=True).strip()


def source_fingerprint():
    names = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--",
         "build.gradle", "settings.gradle", "gradle", "src", "data", "performance/test-environment"], cwd=REPO
    ).decode().split("\0")
    digest = hashlib.sha256()
    for name in sorted(set(names) - {""}):
        path = REPO / name
        digest.update(name.encode())
        digest.update(path.read_bytes() if path.is_file() else b"<deleted>")
    return digest.hexdigest()


def owned_containers(containers, session):
    return [container for container in containers
            if container.get("Labels", {}).get(LABEL) == session
            or (container.get("Image") == "testcontainers/ryuk:0.14.0"
                and container.get("Labels", {}).get("org.testcontainers") == "true"
                and "/testcontainers-ryuk-" + session in container.get("Names", []))]


def event_owned(event, session):
    attributes = event.get("Actor", {}).get("Attributes", {})
    return bool(session) and (attributes.get(LABEL) == session
        or (attributes.get("image") == "testcontainers/ryuk:0.14.0"
            and attributes.get("org.testcontainers") == "true"
            and attributes.get("name") == "testcontainers-ryuk-" + session))


def cleanup_session(docker, directory, errors):
    _, session = jvm_identity(directory / "events.tsv")
    if not session:
        return
    try:
        remaining = owned_containers(docker.containers(), session)
        save(directory / "remaining-containers.json", remaining)
        for container in remaining:
            result = subprocess.run(["docker", "rm", "-f", container["Id"]], capture_output=True, text=True)
            if result.returncode and any(item["Id"] == container["Id"] for item in docker.containers()):
                errors.append("Container cleanup failed: " + container["Id"])
    except Exception as failure:
        errors.append(f"Container cleanup failed: {type(failure).__name__}: {failure}")


class UnixConnection(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__("localhost", timeout=5)
        self.path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.path)


class Docker:
    def __init__(self):
        endpoint = os.environ.get("DOCKER_HOST") or command(
            ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]
        )
        if not endpoint.startswith("unix://"):
            raise ValueError("This runner requires a local Docker Unix socket (Desktop or Linux)")
        self.socket = endpoint[len("unix://"):]
        self.version = ""
        self.version = "/v" + self.get("/version")["ApiVersion"]

    def get(self, path):
        connection = UnixConnection(self.socket)
        try:
            connection.request("GET", self.version + path)
            response = connection.getresponse()
            body = response.read()
            if response.status != 200:
                raise urllib.error.HTTPError("docker:" + path, response.status, response.reason, response.headers, None)
            return json.loads(body)
        finally:
            connection.close()

    def containers(self, session=None):
        filters = {"label": [f"{LABEL}={session}"]} if session else {}
        query = urllib.parse.urlencode({"all": "true", "filters": json.dumps(filters)})
        return self.get("/containers/json?" + query)

    def memory(self, container):
        try:
            state = self.get(f"/containers/{container['Id']}/json")["State"]
            data = self.get(f"/containers/{container['Id']}/stats?stream=false&one-shot=true").get("memory_stats", {}) \
                if state["Running"] else {}
        except urllib.error.HTTPError as failure:
            if failure.code in (404, 409):
                return None
            raise
        cache = data.get("stats", {}).get("total_inactive_file", data.get("stats", {}).get("inactive_file", 0))
        return {"id": container["Id"], "image": container["Image"],
                "memory_bytes": max(0, data.get("usage", 0) - cache),
                "raw_usage_bytes": data.get("usage", 0), "limit_bytes": data.get("limit"), "state": state}


def jvm_identity(path):
    if path.exists():
        for row in csv.reader(path.read_text().splitlines(), delimiter="\t"):
            if len(row) == 5 and row[2] == "jvm_start":
                return int(row[1]), row[3]
    return None, None


def rss_bytes(pid):
    if pid is None:
        return None
    status = Path(f"/proc/{pid}/status")
    if status.exists():
        try:
            match = re.search(r"^VmRSS:\s+(\d+) kB", status.read_text(), re.MULTILINE)
        except FileNotFoundError:
            return None
        return int(match[1]) * 1024 if match else None
    result = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)], text=True, capture_output=True)
    return int(result.stdout.strip()) * 1024 if result.returncode == 0 and result.stdout.strip() else None


def monitor(docker, directory, stop, errors):
    event_process = None
    start = int(time.time())
    try:
        with (directory / "samples.jsonl").open("w") as samples, \
                (directory / "docker-events.jsonl").open("w") as events, \
                (directory / "docker-events.stderr").open("w") as event_errors, \
                ThreadPoolExecutor(max_workers=32) as pool:
            while not stop.is_set():
                started = time.monotonic()
                pid, session = jvm_identity(directory / "events.tsv")
                if session:
                    if event_process is None:
                        event_process = subprocess.Popen(
                            ["docker", "events", "--since", str(start), "--filter", "type=container",
                             "--format", "{{json .}}"], stdout=events, stderr=event_errors
                        )
                    if event_process.poll() is not None:
                        raise RuntimeError("Docker event collection stopped unexpectedly")
                    containers = [container for container in docker.containers(session)
                                  if container["Image"].split(":")[0] in ("mysql", "redis")]
                    memory = [value for value in pool.map(docker.memory, containers) if value is not None]
                    sample = {"epoch_ms": int(time.time() * 1000), "collection_seconds": time.monotonic() - started,
                              "jvm_rss_bytes": rss_bytes(pid), "containers": memory}
                    samples.write(json.dumps(sample) + "\n")
                    samples.flush()
                stop.wait(max(0, 1 - (time.monotonic() - started)))
    except Exception as failure:
        errors.append(f"{type(failure).__name__}: {failure}")
    finally:
        if event_process is not None:
            if event_process.poll() is None:
                event_process.terminate()
            event_process.wait(timeout=10)


def json_lines(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()] if path.exists() else []


def resource_summary(directory, memory_limit):
    samples = json_lines(directory / "samples.jsonl")
    _, session = jvm_identity(directory / "events.tsv")
    events = [event for event in json_lines(directory / "docker-events.jsonl") if event_owned(event, session)]
    resources = report.memory_summary(samples)
    resources["docker_memory_limit_bytes"] = memory_limit
    resources["peak_fraction_of_docker_memory"] = resources["peak_bytes"] / memory_limit if samples else None
    resources["sample_count"] = len(samples)
    resources["target_interval_seconds"] = 1
    resources["max_collection_seconds"] = max((sample["collection_seconds"] for sample in samples), default=None)
    rss = [sample["jvm_rss_bytes"] for sample in samples if sample["jvm_rss_bytes"] is not None]
    resources["jvm_rss_mean_bytes"] = statistics.mean(rss) if rss else None
    resources["jvm_rss_peak_bytes"] = max(rss) if rss else None
    resources["peak_running_mysql"] = max((sum(c["image"].startswith("mysql:") and c["state"]["Running"]
                                              for c in sample["containers"])
                                          for sample in samples), default=0)
    resources["peak_running_redis"] = max((sum(c["image"].startswith("redis:") and c["state"]["Running"]
                                              for c in sample["containers"])
                                          for sample in samples), default=0)
    resources["created"] = {image: len({event["Actor"]["ID"] for event in events
                                       if event.get("Action") == "create"
                                       and event["Actor"]["Attributes"].get("image", "").startswith(image + ":")})
                            for image in ("mysql", "redis")}
    resources["oom_events"] = sum(event.get("Action") == "oom" for event in events)
    # Testcontainers may use SIGKILL for ordinary cleanup; exit 137 alone does not prove OOM.
    resources["nonzero_container_exits"] = [event for event in events if event.get("Action") == "die"
                                            and event["Actor"]["Attributes"].get("exitCode", "0") != "0"]
    resources["oom_inspections"] = [container for sample in samples for container in sample["containers"]
                                    if container.get("state", {}).get("OOMKilled")]
    return resources


def stop_process(process):
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


def prepare(output):
    started = time.monotonic()
    with (output / "prepare.log").open("w") as log:
        subprocess.run(["./gradlew", "testEnvironmentPrepare", "--no-daemon", "--no-watch-fs", "--max-workers=2"],
                       cwd=REPO, stdout=log, stderr=subprocess.STDOUT, check=True)
        for image in ("mysql:9.7.2", "redis:7.4-alpine", "testcontainers/ryuk:0.14.0"):
            result = subprocess.run(["docker", "image", "inspect", image], stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL)
            if result.returncode:
                subprocess.run(["docker", "pull", image], stdout=log, stderr=subprocess.STDOUT, check=True)
    images = [json.loads(command(["docker", "image", "inspect", image]))[0]
              for image in ("mysql:9.7.2", "redis:7.4-alpine", "testcontainers/ryuk:0.14.0")]
    save(output / "prepare.json", {"seconds": time.monotonic() - started,
         "images": [{key: image.get(key) for key in ("Id", "RepoTags", "RepoDigests", "Architecture", "Os")}
                    for image in images]})


def run_variant(docker, root, variant, index, tests, timeout, memory_limit, fingerprint):
    directory = root / f"{index:02d}-{variant}"
    directory.mkdir()
    args = ["./gradlew", TASKS[variant], "--no-daemon", "--no-watch-fs", "--max-workers=2",
            "--no-configuration-cache", "--no-build-cache", f"-PtestEnvironment.output={directory}"]
    for test in tests:
        args += ["--tests", test]
    save(directory / "command.json", args)
    background = [{"id": container["Id"], "image": container["Image"]} for container in docker.containers()
                  if container["State"] == "running"]
    save(directory / "background-containers.json", background)
    stop = threading.Event()
    errors = []
    observer = threading.Thread(target=monitor, args=(docker, directory, stop, errors))
    observer.start()
    started = time.monotonic()
    timed_out = False
    start_epoch_ns = time.time_ns()
    try:
        with (directory / "gradle.log").open("w") as log:
            process = subprocess.Popen(args, cwd=REPO, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                stop_process(process)
    except BaseException:
        if "process" in locals() and process.poll() is None:
            stop_process(process)
        raise
    finally:
        elapsed = time.monotonic() - started
        end_epoch_ns = time.time_ns()
        stop.set()
        observer.join()
        cleanup_session(docker, directory, errors)
    pid, session = jvm_identity(directory / "events.tsv")
    background_events = [event for event in json_lines(directory / "docker-events.jsonl")
                         if start_epoch_ns <= event.get("timeNano", 0) <= end_epoch_ns
                         and not event_owned(event, session)
                         and event.get("Action") in ("create", "start", "die", "destroy", "pause", "unpause", "update")]
    save(directory / "background-events.json", background_events)
    resources = resource_summary(directory, memory_limit)
    inventory = report.test_results(directory / "xml")
    timings = [directory / name for name in ("task-start-ms.txt", "task-end-ms.txt")]
    task_seconds = (int(timings[1].read_text()) - int(timings[0].read_text())) / 1000 \
        if all(path.exists() for path in timings) else None
    result = {"variant": variant, "directory": str(directory), "exit_code": process.returncode,
              "timed_out": timed_out, "gradle_seconds": elapsed, "test_task_seconds": task_seconds,
              "tests": inventory, "resources": resources, "contexts": report.context_summary(directory / "events.tsv"),
              "observation_errors": errors, "background_containers": background,
              "source_unchanged": source_fingerprint() == fingerprint,
              "background_unchanged": not background_events}
    result["valid"] = (process.returncode == 0 and bool(inventory)
                       and all(test["status"] == "passed" for test in inventory)
                       and session is not None and resources["sample_count"] > 0
                       and resources["oom_events"] == 0 and not resources["oom_inspections"] and not errors
                       and result["source_unchanged"] and result["background_unchanged"])
    save(directory / "summary.json", result)
    print(f"{variant}: {'valid' if result['valid'] else 'invalid'}, {len(inventory)} tests, {elapsed:.1f}s", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "run"))
    parser.add_argument("--variants", nargs="+", choices=TASKS, default=["A", "B", "B", "A", "A", "B"])
    parser.add_argument("--tests", action="append", default=[], help="Optional smoke filter; recorded in command.json")
    parser.add_argument("--timeout", type=int, default=1200, help="Maximum seconds per run")
    parser.add_argument("--output", type=Path, help="New output directory; must not already exist")
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("--timeout must be positive")
    if any(os.environ.get(key) for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS")):
        parser.error("Unset JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS and GRADLE_OPTS for reproducible JVM settings")
    lock_path = REPO / "build/test-environment.lock"
    lock_path.parent.mkdir(exist_ok=True)
    with lock_path.open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error("Another test-environment comparison is running in this worktree")
        processes = command(["ps", "-Ao", "args="])
        if any("GradleWorkerMain" in line and str(REPO / "build/tmp") in line for line in processes.splitlines()):
            parser.error("A Gradle test JVM is already running in this worktree; wait for it to finish")
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        output = (args.output or REPO / "performance/test-environment/results" / stamp).resolve()
        output.mkdir(parents=True, exist_ok=False)
        docker = Docker()
        info = docker.get("/info")
        fingerprint = source_fingerprint()
        save(output / "environment.json", {"commit": command(["git", "rev-parse", "HEAD"]),
             "status": command(["git", "status", "--short"]), "java": command(["./gradlew", "--version"]),
             "docker": {"memory_bytes": info["MemTotal"], "cpus": info["NCPU"], "server_version": info["ServerVersion"]},
             "test_filters": args.tests, "variants": args.variants, "jvm_heap_mib": 512, "test_forks": 1,
             "source_sha256": fingerprint,
             "host_cgroup_memory_max": Path("/sys/fs/cgroup/memory.max").read_text().strip()
                 if Path("/sys/fs/cgroup/memory.max").exists() else None})
        (output / "changes.patch").write_text(command(["git", "diff", "HEAD", "--binary"]))
        untracked = command(["git", "ls-files", "--others", "--exclude-standard"]).splitlines()
        save(output / "untracked-files.json", {name: (REPO / name).read_text()
                                               for name in untracked if (REPO / name).is_file()})
        prepare(output)
        if args.action == "prepare":
            print(f"Results: {output}")
            return 0
        runs = []
        for index, variant in enumerate(args.variants, 1):
            runs.append(run_variant(docker, output, variant, index, args.tests, args.timeout, info["MemTotal"], fingerprint))
            save(output / "runs.json", runs)
        background_unchanged = all(sorted(run["background_containers"], key=lambda c: c["id"])
                                   == sorted(runs[0]["background_containers"], key=lambda c: c["id"]) for run in runs)
        valid_comparison = report.comparable(runs) and background_unchanged
        aggregates = {}
        for variant in dict.fromkeys(args.variants):
            successful = [run for run in runs if run["variant"] == variant and run["valid"]]
            times = [run["gradle_seconds"] for run in successful]
            aggregates[variant] = {"successful_runs": len(successful), "runs": args.variants.count(variant),
                                   "gradle_seconds_median": statistics.median(times) if times else None,
                                   "gradle_seconds_min": min(times) if times else None,
                                   "gradle_seconds_max": max(times) if times else None}
        save(output / "comparison.json", {"comparable": valid_comparison, "smoke_only": bool(args.tests),
                                           "background_container_inventory_unchanged": background_unchanged,
                                           "variants": aggregates})
        print(f"Results: {output}")
        return 0 if valid_comparison else 1


if __name__ == "__main__":
    raise SystemExit(main())
