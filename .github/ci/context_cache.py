"""Analyze CI observer events. Equality IDs are local to one Worker, not cross-run hashes."""

from collections import Counter, defaultdict
import json
from pathlib import Path


COMPONENTS = {
    "configType",
    "locations",
    "classes",
    "initializers",
    "profiles",
    "propertyDescriptors",
    "properties",
    "customizers",
    "loader",
    "parent",
    "resourceBasePath",
}


def analyze(directory: Path, expected_misses=None):
    files = sorted(directory.glob("context-events-*.jsonl"))
    errors, keys, snapshots, loads, closes = [], {}, [], [], []
    for path in files:
        sequence = 0
        try:
            for line in path.read_text().splitlines():
                row = json.loads(line)
                if row["sequence"] <= sequence or row["springVersion"] != "7.0.9":
                    raise ValueError("Unsupported version or unordered events")
                sequence = row["sequence"]
                worker = str(row["worker"])
                if not worker.isdigit():
                    raise ValueError("Missing Worker identity")
                event = row["event"]
                if event == "error":
                    errors.append("Observer reported an instrumentation error")
                elif event == "key":
                    identity = (worker, row["key"])
                    if identity in keys or set(row["components"]) != COMPONENTS:
                        raise ValueError("Unexpected key schema")
                    if not all(
                        isinstance(v, int) and v >= 0
                        for v in row["components"].values()
                    ):
                        raise ValueError("Unexpected component identity")
                    keys[identity] = row
                elif event == "snapshot":
                    if row["size"] != len(row["resident"]):
                        raise ValueError("Inconsistent cache size")
                    snapshots.append(row)
                elif event == "discovered":
                    loads.append(row)
                elif event == "closed":
                    if row["reason"] not in ("lru", "dirties", "explicit", "other"):
                        raise ValueError("Unknown close reason")
                    closes.append(row)
                else:
                    raise ValueError("Unexpected event type")
        except (ValueError, KeyError, TypeError, OSError):
            errors.append("Unreadable or unexpected observer event file")
    generations, cache_stats = defaultdict(list), {}
    classes = defaultdict(set)
    for row in snapshots:
        worker, cache = str(row["worker"]), row["cache"]
        identity = (worker, row["key"])
        if identity not in keys:
            errors.append("Snapshot key missing from registry")
        if row["key"] in row["resident"]:
            classes[identity].add(row["testClass"])
        stats = cache_stats.setdefault(
            (worker, cache), {"hits": 0, "misses": 0, "maxObservedSize": 0}
        )
        stats["hits"] = max(stats["hits"], row["hits"])
        stats["misses"] = max(stats["misses"], row["misses"])
        stats["maxObservedSize"] = max(stats["maxObservedSize"], row["size"])
    for row in loads:
        identity = (str(row["worker"]), row["key"])
        if identity not in keys:
            errors.append("Discovered key missing from registry")
        generations[identity].append(row)
    if any(sum(k[0] == worker for k in cache_stats) != 1 for worker, _ in cache_stats):
        errors.append(
            "Multiple cache instances in one Worker are outside this diagnostic scope"
        )
    close_by_generation = {}
    for row in closes:
        identity = (str(row["worker"]), row["key"], row["generation"])
        if identity in close_by_generation:
            errors.append("Duplicate close event")
        close_by_generation[identity] = row
    reloads = []
    for identity, entries in generations.items():
        entries.sort(key=lambda row: row["generation"])
        if [r["generation"] for r in entries] != list(range(1, len(entries) + 1)):
            errors.append("Missing generation")
        for row in entries[1:]:
            previous = close_by_generation.get((*identity, row["generation"] - 1))
            reason = (
                previous["reason"]
                if previous and previous["sequence"] < row["sequence"]
                else "unobserved"
            )
            reloads.append(
                {
                    "worker": identity[0],
                    "key": identity[1],
                    "generation": row["generation"],
                    "after": reason,
                }
            )
    native_misses = sum(x["misses"] for x in cache_stats.values())
    # Boundary snapshots may miss a Context that is created AND removed between callbacks.
    # Such a run is incomplete, not evidence of zero reloads.
    if not files or not loads or native_misses != len(loads):
        errors.append("Discovery coverage does not reconcile with cache misses")
    if expected_misses is not None and native_misses != expected_misses:
        errors.append(
            "Snapshot misses do not reconcile with native Spring log counters"
        )
    if any(reason == "unobserved" for reason in (row["after"] for row in reloads)):
        errors.append("Reload without observed preceding close")
    related = []
    loaded_keys = [identity for identity in generations if identity in keys]
    for identity in loaded_keys:
        candidates = []
        for other in loaded_keys:
            if other == identity or other[0] != identity[0]:
                continue
            differences = sorted(
                k
                for k in COMPONENTS
                if keys[identity]["components"][k] != keys[other]["components"][k]
            )
            if not differences:
                errors.append(
                    "Distinct Spring keys have indistinguishable component groups"
                )
            candidates.append((len(differences), other[1], differences))
        if candidates:
            _, nearest, differences = min(candidates)
            related.append(
                {
                    "worker": identity[0],
                    "key": identity[1],
                    "nearestKey": nearest,
                    "differentComponents": differences,
                }
            )
    return {
        "complete": not errors,
        "errors": sorted(set(errors)),
        "eventFiles": len(files),
        "registeredKeys": len(keys),
        "loadedKeys": len(generations),
        "contextsDiscovered": len(loads),
        "recreations": len(reloads),
        "reloadReasons": dict(Counter(r["after"] for r in reloads)),
        "closeReasons": dict(Counter(r["reason"] for r in closes)),
        "cacheStats": [
            {"worker": k[0], "cache": k[1], **v} for k, v in sorted(cache_stats.items())
        ],
        "nativeMissesAtSnapshots": native_misses,
        "keys": [
            {
                "worker": k[0],
                "key": k[1],
                "components": row["components"],
                "configurationClasses": row["classes"],
                "customizers": row["customizers"],
                "testClasses": sorted(classes[k]),
                "generations": len(generations.get(k, [])),
            }
            for k, row in sorted(keys.items())
        ],
        "reloads": reloads,
        "nearestRelatedKeys": related,
        "limits": [
            "IDs are assigned using actual Spring equals(), local to a Worker; never compare their numbers across runs.",
            "Private-field observation is pinned to Spring7.0.9. Unsupported versions or incomplete discovery fail the report.",
            "Snapshots read resident entries without cache.get()/context loading and do not update LRU order.",
            "Close reasons come from the synchronous close stack. Other closures are not assumed to be LRU or DirtiesContext.",
            "Discovery timestamps are callback boundaries, not exact load durations. Initial refresh/discovery time is not startup CPU time.",
            "Same key can serve several classes. Key-component differences describe configuration, not independent causal costs.",
            "Added Listener, metadata retention and file I/O add measurement overhead. Do not compare directly with #305 timings.",
            "Method-level cache hits are cumulative reads, not a count of reused test classes.",
        ],
    }


def attach_timings(data, walls, spring_starts):
    by_class = defaultdict(list)
    for key in data["keys"]:
        key["classWallObservations"] = []
        key["springStartupObservations"] = []
        for name in key["testClasses"]:
            by_class[name].append(key)
    unmatched = 0
    for row in walls:
        matches = by_class[row["className"]]
        if len(matches) == 1:
            matches[0]["classWallObservations"].append(
                {"testClass": row["className"], "seconds": row["elapsedSeconds"]}
            )
    for row in spring_starts:
        matches = by_class[row.get("sourceTestClass", "")]
        if len(matches) == 1:
            matches[0]["springStartupObservations"].append(
                {"testClass": row["sourceTestClass"], "seconds": row["seconds"]}
            )
        else:
            unmatched += 1
    data["unmatchedSpringStartupObservations"] = unmatched
    data["limits"].append(
        "Startup observations are joined by the JUnit source class. Class wall time is not Context startup time; overlapping observations are not added to elapsed time."
    )


def markdown(data):
    lines = [
        "### Spring Context cache",
        "",
        f"- Complete: {data['complete']}; loaded keys: {data['loadedKeys']}; contexts: {data['contextsDiscovered']}; recreations: {data['recreations']}",
        f"- Reload reasons: {json.dumps(data['reloadReasons'])}; close reasons: {json.dumps(data['closeReasons'])}",
        "",
        "| Worker | Key | Generations | Classes reusing key |",
        "| --- | ---: | ---: | ---: |",
    ]
    for key in sorted(
        data["keys"],
        key=lambda row: (
            -row["generations"],
            -len(row["testClasses"]),
            row["worker"],
            row["key"],
        ),
    )[:20]:
        lines.append(
            f"| {key['worker']} | {key['key']} | {key['generations']} | {len(key['testClasses'])} |"
        )
    lines += ["", *["- " + item for item in data["limits"]]]
    return "\n".join(lines) + "\n"
