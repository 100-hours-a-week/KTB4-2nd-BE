#!/usr/bin/env python3
"""Test-only command wrapper. Inherits stdout/stderr without parsing or buffering."""
import ctypes
import json
import os
from pathlib import Path
import signal
import sys
import time


def children():
    if sys.platform != 'linux':
        return []
    result = []
    for entry in Path('/proc').iterdir():
        if entry.name.isdigit():
            try:
                fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
                if int(fields[1]) == os.getpid():
                    result.append(int(entry.name))
            except (FileNotFoundError, ProcessLookupError):
                pass
    return result


def collect(argv, directory, timeout, grace=5):
    if not argv or timeout <= 0 or grace < 0:
        raise ValueError('command/deadline missing')
    if sys.platform == 'linux':
        if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), 'PR_SET_CHILD_SUBREAPER')
    started = time.monotonic_ns()
    interrupted = []
    old = {sig: signal.signal(sig, lambda number, frame: interrupted.append(number))
           for sig in (signal.SIGINT, signal.SIGTERM)}
    pid = os.fork()
    if pid == 0:
        os.setsid()
        for sig in old:
            signal.signal(sig, signal.SIG_DFL)
        try:
            os.execvp(argv[0], argv)
        except OSError:
            os._exit(127)
    sample = {'pid': pid, 'wrapper_pid': os.getpid(), 'collector': 'wait4', 'timeout': False,
              'user_cpu_ms': 0, 'system_cpu_ms': 0, 'max_rss_bytes': 0, 'reaped_children': 0,
              'residual_pids': [], 'start_ns':started,'epoch_ms':int(time.time()*1000), 'command': Path(argv[0]).name}
    for key in ('REQUEST_ID', 'JOB_ID', 'WORKER_BATCH_ID', 'WORKER_PHOTO_INDEX', 'WORKER_FORMAT', 'STAGE'):
        sample[key.lower()] = os.getenv('YEODAM_PERF_' + key)
    deadline = started / 1e9 + timeout
    term_at = None
    main_status = None
    group_gone = False
    try:
        while True:
            no_children = False
            try:
                reaped, status, usage = os.wait4(-1, os.WNOHANG)
                if reaped:
                    sample['reaped_children'] += 1
                    sample['user_cpu_ms'] += usage.ru_utime * 1000
                    sample['system_cpu_ms'] += usage.ru_stime * 1000
                    sample['max_rss_bytes'] = max(sample['max_rss_bytes'], usage.ru_maxrss * (1024 if sys.platform == 'linux' else 1))
                    if reaped == pid:
                        main_status = os.waitstatus_to_exitcode(status)
            except ChildProcessError:
                no_children = True
            try:
                os.killpg(pid, 0)
                group_gone = False
            except ProcessLookupError:
                group_gone = True
            if main_status is not None and no_children and group_gone:
                break
            now = time.monotonic()
            if term_at is None and (now >= deadline or interrupted or main_status is not None):
                sample['timeout'] = now >= deadline
                term_at = now
            if term_at is not None:
                sig = signal.SIGKILL if now - term_at >= grace else signal.SIGTERM
                try:
                    os.killpg(pid, sig)
                except ProcessLookupError:
                    pass
                # Adopted delegates may have escaped the original process group.
                for child in children():
                    try:
                        os.kill(child, sig)
                    except ProcessLookupError:
                        pass
            time.sleep(0.01)
        sample['exit_code'] = main_status
        sample['interrupted_signal'] = interrupted[0] if interrupted else None
        sample['end_ns']=time.monotonic_ns()
        sample['wall_ms'] = (sample['end_ns'] - started) / 1e6
        sample['residual_pids'] = children()
        target = Path(directory)
        target.mkdir(parents=True, exist_ok=True)
        path = target / (str(os.getpid()) + '-' + str(started) + '.json')
        temp = path.with_suffix('.tmp')
        temp.write_text(json.dumps(sample))
        temp.replace(path)
        return 124 if sample['timeout'] else (128 + interrupted[0] if interrupted else max(0, main_status) if main_status >= 0 else 128 - main_status)
    finally:
        for sig, handler in old.items():
            signal.signal(sig, handler)


def main():
    name = Path(sys.argv[0]).name
    if name in ('convert', 'exiftool'):
        argv = ['/usr/bin/' + name] + sys.argv[1:]
    elif sys.argv[1:2] == ['--']:
        argv = sys.argv[2:]
    else:
        raise ValueError('invoke via convert/exiftool symlink or command.py -- command')
    return collect(argv, os.environ['YEODAM_PERF_COMMAND_SAMPLES'],
                   float(os.getenv('YEODAM_PERF_COMMAND_TIMEOUT_SECONDS', '120')),
                   float(os.getenv('YEODAM_PERF_COMMAND_GRACE_SECONDS', '5')))


if __name__ == '__main__':
    sys.exit(main())
