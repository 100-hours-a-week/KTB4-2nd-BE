#!/usr/bin/env python3
"""Export the preserved RDB commit with the same harness, without resetting a branch."""
import argparse
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import run


def prepare(target, ref='594f24730741e6f89d06eaf0b515be28c4d44375'):
    # Ref is resolved locally; archive contains tracked files only, never local dirty changes.
    commit = run.command(['git', 'rev-parse', ref + '^{commit}'], cwd=run.REPO).strip()
    target.mkdir(parents=True, exist_ok=False)
    try:
        archive = subprocess.run(['git', 'archive', commit], cwd=run.REPO, check=True, capture_output=True).stdout
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            for member in tar.getmembers():
                if member.issym() or member.islnk() or Path(member.name).is_absolute() or '..' in Path(member.name).parts:
                    raise ValueError('Unsafe archive member')
            for member in tar.getmembers():
                path = target / member.name
                if member.isdir(): path.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as source, path.open('wb') as output: shutil.copyfileobj(source, output)
                    path.chmod(member.mode & 0o755)
                else: raise ValueError('Unsupported archive member')
        for relative in ['performance/auth', 'src/performanceTest/java/com/yeodam/yeodambe/user/performance']:
            shutil.copytree(run.REPO / relative, target / relative, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns('__pycache__'))
        # Compile the Redis fixture helper against existing Spring Redis; runtime Store stays RDB.
        gradle = (run.REPO / 'build.gradle').read_text()
        extra = gradle[gradle.index('// The launcher already exists'):]
        with (target / 'build.gradle').open('a') as f:
            f.write("\ndependencies { performanceTestImplementation 'org.springframework.boot:spring-boot-starter-data-redis' }\n")
            f.write(extra)
        (target / 'auth-capacity-source.json').write_text(json.dumps({'commit': commit, 'kind': 'tracked-source-export'}))
        print(target)
    except Exception:
        # Preserve failed exports for inspection; no recursive deletion of user paths.
        raise


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('target', type=Path)
    args=p.parse_args()
    prepare(args.target.resolve())
