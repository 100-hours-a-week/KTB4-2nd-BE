"""Export a pinned backend revision without changing the caller's checkout."""
import argparse
import hashlib
import io
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
BASELINE = "3ef3260d9703614c69391ecec7f45b1fa8e8943b"

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", default=BASELINE)
    args = parser.parse_args()
    revision = subprocess.check_output(
        ["git", "rev-parse", "--verify", args.revision + "^{commit}"], cwd=REPO, text=True).strip()
    target = ROOT / ".runtime" / "backend-source"
    if target.exists():
        raise SystemExit(".runtime/backend-source already exists; preserve results and remove that directory before preparing another revision.")
    archive = subprocess.check_output(["git", "archive", "--format=zip", revision], cwd=REPO)
    target.mkdir(parents=True)
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        source.extractall(target)
    metadata = {"backend_revision": revision, "archive_sha256": hashlib.sha256(archive).hexdigest()}
    (ROOT / ".runtime" / "source.json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))
    print("Next: docker compose -p yeodam-pollbase -f performance/status-delivery/compose.json build be1")
