"""CI-only measurement. Never upload raw test output, exception bodies or env dumps."""

import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

OUTPUT = Path("build/ci-timing")
SAFE_CLASS = re.compile(r"^[A-Za-z_$][\w.$]*$")
CACHE = re.compile(
    r"DefaultContextCache@(\w+).*?size\s*=\s*(\d+).*?maxSize\s*=\s*(\d+).*?hitCount\s*=\s*(\d+).*?missCount\s*=\s*(\d+)"
)
CONTAINER = re.compile(r"Container (\S+) started in (?:PT)?(?:(\d+)M)?([\d.]+)[sS]")
FLYWAY = re.compile(
    r"Successfully (validated|applied) (\d+) migrations?.*?execution time ([\d:.]+)"
)
BOOT = re.compile(r"Started ([\w.$]+) in ([\d.]+) seconds")


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def collect_observations(text, observations):
    # Retain numeric evidence only. Context counters are cumulative: take maxima per ID.
    for cache_id, size, limit, hits, misses in CACHE.findall(text):
        current = observations["contextCaches"].setdefault(
            cache_id,
            {"maxObservedSize": 0, "limit": int(limit), "hits": 0, "misses": 0},
        )
        current["maxObservedSize"] = max(current["maxObservedSize"], int(size))
        current["hits"] = max(current["hits"], int(hits))
        current["misses"] = max(current["misses"], int(misses))
    for image, minutes, seconds in CONTAINER.findall(text):
        # Testcontainers logger may include arbitrary image text; restrict published identifiers.
        if re.fullmatch(r"[\w./:@-]+", image):
            observations["containerStarts"].append(
                {"image": image, "seconds": int(minutes or 0) * 60 + float(seconds)}
            )
    for action, count, duration in FLYWAY.findall(text):
        parts = [float(value) for value in duration.split(":")]
        seconds = sum(value * 60**index for index, value in enumerate(reversed(parts)))
        observations["flyway"].append(
            {"action": action, "migrations": int(count), "seconds": seconds}
        )
    for name, seconds in BOOT.findall(text):
        observations["springStarts"].append(
            {"className": name, "seconds": float(seconds)}
        )


def summarize(root):
    observations = {
        "contextCaches": {},
        "containerStarts": [],
        "flyway": [],
        "springStarts": [],
    }
    classes, methods, errors = [], [], []
    xml_files = sorted((root / "test-results/test").glob("TEST-*.xml"))
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in xml_files:
        try:
            suite = ET.parse(path).getroot()
            name = suite.attrib["name"]
            if not SAFE_CLASS.fullmatch(name):
                raise ValueError("unexpected suite identifier")
            for key in totals:
                totals[key] += int(suite.get(key, 0))
            classes.append(
                {"className": name, "junitSeconds": float(suite.get("time", 0))}
            )
            for case in suite.findall("testcase"):
                # Parameterized display names may carry tokens/data; publish only class timings.
                methods.append(float(case.get("time", 0)))
            for node in suite.findall("system-out") + suite.findall("system-err"):
                collect_observations(node.text or "", observations)
        except (ET.ParseError, ValueError, KeyError):
            errors.append("unreadable or unexpected JUnit XML")
    walls = []
    for path in sorted((root / "ci-timing").glob("*-classes.jsonl")):
        for line in path.read_text().splitlines():
            try:
                entry = json.loads(line)
                if not SAFE_CLASS.fullmatch(entry["className"]):
                    raise ValueError("unexpected class identifier")
                walls.append(entry)
            except (ValueError, KeyError):
                errors.append("invalid class timing record")
    profiles = sorted((root / "reports/profile").glob("*.html"))
    if {row["className"] for row in classes} - {row["className"] for row in walls}:
        errors.append("JUnit classes missing from listener timing")
    complete = bool(xml_files and walls and profiles) and not errors
    return {
        "complete": complete,
        "errors": errors,
        "junitFiles": len(xml_files),
        "totals": totals,
        "classWallIntervals": sorted(
            walls, key=lambda row: row["elapsedSeconds"], reverse=True
        ),
        "junitClasses": sorted(
            classes, key=lambda row: row["junitSeconds"], reverse=True
        ),
        "testcaseSecondsSum": sum(methods),
        "observations": observations,
        "profileFiles": [str(path.relative_to(root)) for path in profiles],
        "limits": [
            "Class/JUnit/Context/container/Flyway intervals overlap; do not add them to elapsed time.",
            "Log observations can be absent or repeated; absence is unknown, not zero startup.",
            "Context counters are maxima per cache ID within one command, not sums of log lines.",
            "DEBUG logging and profile/listener recording add overhead; compare under the same instrumentation.",
            "A completed report may contain failing/skipped tests; read command exit and totals too.",
        ],
    }


def run():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name in [
        "build.gradle",
        "gradle/wrapper/gradle-wrapper.properties",
        ".github/workflows/backend-pr-gate.yml",
        ".github/ci/test-timing.init.gradle",
        ".github/ci/test_timing.py",
    ]:
        hashes[name] = hashlib.sha256(Path(name).read_bytes()).hexdigest()
    metadata = {
        key: os.environ.get(key)
        for key in [
            "GITHUB_SHA",
            "GITHUB_RUN_ID",
            "GITHUB_RUN_ATTEMPT",
            "PR_HEAD_SHA",
            "RUNNER_OS",
            "RUNNER_ARCH",
            "ImageOS",
            "ImageVersion",
            "GRADLE_CACHE_HIT",
        ]
    }
    metadata.update(
        {
            "checkoutSha": subprocess.check_output(
                ["git", "rev-parse", "HEAD"], text=True
            ).strip(),
            "logicalCpu": os.cpu_count(),
            "machine": platform.machine(),
            "fileHashes": hashes,
        }
    )
    if Path("/proc/meminfo").exists():
        metadata["memoryTotalKiB"] = int(
            re.search(r"MemTotal:\s+(\d+)", Path("/proc/meminfo").read_text()).group(1)
        )
    java = subprocess.run(
        ["java", "-version"], text=True, capture_output=True, check=True
    )
    metadata["runnerJavaVersion"] = (java.stdout + java.stderr).splitlines()[:3]
    command = [
        "./gradlew",
        "test",
        "--no-daemon",
        "--no-watch-fs",
        "--profile",
        "-I",
        ".github/ci/test-timing.init.gradle",
    ]
    metadata.update(
        {"command": command, "startedAt": datetime.now(timezone.utc).isoformat()}
    )
    save(OUTPUT / "command.json", metadata)
    start = time.monotonic()
    result = subprocess.run(command, check=False)
    metadata.update(
        {
            "elapsedSeconds": time.monotonic() - start,
            "exitCode": result.returncode,
            "endedAt": datetime.now(timezone.utc).isoformat(),
        }
    )
    save(OUTPUT / "command.json", metadata)
    return result.returncode


def report():
    start = time.monotonic()
    data = summarize(Path("build"))
    data["reportGenerationSeconds"] = time.monotonic() - start
    save(OUTPUT / "summary.json", data)
    command_path = OUTPUT / "command.json"
    command = json.loads(command_path.read_text()) if command_path.exists() else {}
    lines = [
        "## BE test measurement",
        "",
        f"- Command elapsed: {command.get('elapsedSeconds', 'unknown')} seconds; exit: {command.get('exitCode', 'unknown')}",
        f"- JUnit totals: {data['totals']}; report complete: {data['complete']}",
        "- Full Gradle task/configuration timings: profile HTML in artifact.",
        "",
        "| Class | Wall seconds | Tests incl. nested | Failures incl. nested |",
        "| --- | ---: | ---: | ---: |",
    ]
    for row in data["classWallIntervals"][:20]:
        lines.append(
            f"| {row['className']} | {row['elapsedSeconds']:.3f} | {row['testsIncludingNested']} | {row['failuresIncludingNested']} |"
        )
    lines += [
        "",
        "### Observations",
        "",
        f"- Context caches: {json.dumps(data['observations']['contextCaches'])}",
        f"- Container starts: {len(data['observations']['containerStarts'])}; Flyway entries: {len(data['observations']['flyway'])}; Spring starts: {len(data['observations']['springStarts'])}",
        "",
        *["- " + item for item in data["limits"]],
    ]
    markdown = "\n".join(lines) + "\n"
    (OUTPUT / "summary.md").write_text(markdown)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(markdown)
    print(markdown)
    return 0 if data["complete"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["run", "report"])
    args = parser.parse_args()
    sys.exit(run() if args.action == "run" else report())
