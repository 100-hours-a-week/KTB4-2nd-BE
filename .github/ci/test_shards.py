"""Partition compiled test classes; retain all tests and compare opaque JUnit identities."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def partition(directories, hints, count):
    if count not in (1, 2):
        raise ValueError("only one or two Runners are supported")
    inventory = {}
    for directory in directories:
        for path in sorted(Path(directory).rglob("*.class")):
            name = path.relative_to(directory).as_posix()[:-6].replace("/", ".")
            if name in inventory:
                raise ValueError("duplicate compiled class")
            inventory[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not inventory:
        raise ValueError("compiled test classes are missing")
    # Anonymous/nested classes always follow their enclosing top-level class.
    roots = {name.split("$")[0] for name in inventory}
    parent = {name: name for name in roots}

    def find(name):
        while parent[name] != name:
            name = parent[name]
        return name

    for group in hints["contextGroups"]:
        present = sorted({name.split("$")[0] for name in group} & roots)
        for name in present[1:]:
            parent[find(name)] = find(present[0])
    groups = {}
    for name in sorted(roots):
        groups.setdefault(find(name), []).append(name)
    weights = hints["classSeconds"]
    weighted = [
        (sum(weights.get(name, 1.0) for name in group), group)
        for group in groups.values()
    ]
    # Largest group first, deterministic tie breaks. Timing hints never select scope.
    loads = [0.0] * count
    owners = {}
    for weight, group in sorted(weighted, key=lambda item: (-item[0], item[1])):
        owner = min(range(count), key=lambda index: (loads[index], index))
        loads[owner] += weight
        owners.update({name: owner + 1 for name in group})
    return {
        "runnerCount": count,
        "compiledClasses": inventory,
        "compiledInventoryHash": digest(inventory),
        "owners": {name: owners[name.split("$")[0]] for name in inventory},
        "estimatedClassSeconds": loads,
        "hintsHash": digest(hints),
    }


def aggregate(root, count):
    reports = sorted(root.rglob("summary.json"))
    if len(reports) != count:
        raise ValueError("missing or extra shard reports")
    commands = [json.loads(path.with_name("command.json").read_text()) for path in reports]
    plans = [json.loads(path.with_name("shard-plan.json").read_text()) for path in reports]
    if len({digest(plan) for plan in plans}) != 1 or plans[0]["runnerCount"] != count:
        raise ValueError("shard plans or compiled inputs differ")
    if len({cmd["checkoutSha"] for cmd in commands}) != 1:
        raise ValueError("shards used different checkouts")
    if {cmd["testShard"] for cmd in commands} != set(range(1, count + 1)):
        raise ValueError("missing or duplicate shard index")
    totals = Counter()
    cases = Counter()
    suites = set()
    for path, cmd in zip(reports, commands):
        data = json.loads(path.read_text())
        if not data["complete"] or cmd["exitCode"] != 0:
            raise ValueError("incomplete or failed test execution")
        if data["totals"]["failures"] or data["totals"]["errors"]:
            raise ValueError("test failures or errors")
        if cmd["requestedTestForks"] != 1 or cmd["testRunnerCount"] != count:
            raise ValueError("unexpected JVM/Runner count")
        names = {item["className"] for item in data["junitClasses"]}
        if suites & names:
            raise ValueError("a test suite ran on more than one Runner")
        for name in names:
            if plans[0]["owners"].get(name.split("$")[0]) != cmd["testShard"]:
                raise ValueError("suite executed on the wrong Runner")
        suites |= names
        totals.update(data["totals"])
        if sum(data["testIdentities"].values()) != data["totals"]["tests"]:
            raise ValueError("JUnit test identity count mismatch")
        cases.update(data["testIdentities"])
    return {
        "runnerCount": count,
        "checkoutSha": commands[0]["checkoutSha"],
        "compiledInventoryHash": plans[0]["compiledInventoryHash"],
        "hintsHash": plans[0]["hintsHash"],
        "totals": dict(totals),
        "testIdentities": dict(sorted(cases.items())),
        "suiteNames": sorted(suites),
    }


def compare(baseline, candidate):
    for key in ("checkoutSha", "compiledInventoryHash", "hintsHash", "totals", "testIdentities", "suiteNames"):
        if baseline[key] != candidate[key]:
            raise ValueError(f"baseline and shards differ: {key}")
    return {"equivalent": True, "tests": baseline["totals"]["tests"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    plan = sub.add_parser("plan")
    plan.add_argument("--classes-dir", action="append", required=True, type=Path)
    plan.add_argument("--hints", type=Path, required=True)
    plan.add_argument("--count", type=int, required=True)
    plan.add_argument("--output", type=Path, required=True)
    check = sub.add_parser("aggregate")
    check.add_argument("--root", type=Path, required=True)
    check.add_argument("--count", type=int, required=True)
    check.add_argument("--output", type=Path, required=True)
    comparison = sub.add_parser("compare")
    comparison.add_argument("baseline", type=Path)
    comparison.add_argument("candidate", type=Path)
    args = parser.parse_args()
    if args.action == "plan":
        data = partition(args.classes_dir, json.loads(args.hints.read_text()), args.count)
    elif args.action == "aggregate":
        data = aggregate(args.root, args.count)
    else:
        print(json.dumps(compare(json.loads(args.baseline.read_text()), json.loads(args.candidate.read_text()))))
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2) + "\n")
    print(json.dumps({key: value for key, value in data.items() if key not in ("compiledClasses", "owners", "testIdentities", "suiteNames")}))


if __name__ == "__main__":
    main()
