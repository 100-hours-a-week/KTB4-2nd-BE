import csv
from collections import Counter
import statistics
import xml.etree.ElementTree as ET


def comparable(runs):
    if not runs or any(not run["valid"] or not run["tests"] for run in runs):
        return False
    inventories = [sorted((test["class"], test["name"], test["status"], test.get("reason", ""))
                          for test in run["tests"]) for run in runs]
    return all(inventory == inventories[0] for inventory in inventories)


def test_results(directory):
    results = []
    for path in sorted(directory.glob("TEST-*.xml")):
        for test in ET.parse(path).getroot().iter("testcase"):
            status, reason = "passed", ""
            for tag in ("failure", "error", "skipped"):
                detail = test.find(tag)
                if detail is not None:
                    status = "skipped" if tag == "skipped" else "failed"
                    reason = detail.get("message", "") or detail.text or ""
                    break
            results.append({"class": test.get("classname"), "name": test.get("name"),
                            "status": status, "reason": reason, "seconds": float(test.get("time", 0))})
    return results


def memory_summary(samples):
    totals = [sum(container["memory_bytes"] for container in sample["containers"]) for sample in samples]
    return {"peak_bytes": max(totals) if totals else None,
            "mean_bytes": statistics.mean(totals) if totals else None}


def context_summary(path):
    rows = list(csv.reader(path.read_text().splitlines(), delimiter="\t")) if path.exists() else []
    active = set()
    peak = created = 0
    configurations = Counter()
    for epoch, pid, event, identifier, detail in rows:
        if event == "context_refresh":
            created += 1
            configurations[detail] += 1
            active.add(identifier)
            peak = max(peak, len(active))
        elif event == "context_close":
            active.discard(identifier)
    starts = [int(row[4]) for row in rows if row[2] == "jvm_start"]
    ends = [int(row[0]) for row in rows if row[2] == "jvm_end"]
    return {"created": created, "peak_active": peak,
            "recreated": sum(max(0, count - 1) for count in configurations.values()),
            "jvm_start_to_shutdown_hook_seconds": (max(ends) - min(starts)) / 1000 if starts and ends else None,
            "last_context_close_ms": max((int(row[0]) for row in rows if row[2] == "context_close"), default=None)}
