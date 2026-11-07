#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Retain bounded JVM/OS diagnostics after a failed runtime job."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import time


def collect(results, diagnostic_roots=()):
    results.mkdir(parents=True, exist_ok=True)
    destination = results / 'crash-reports'
    destination.mkdir(exist_ok=True)
    receipt = results / 'receipt.json'
    cutoff = (receipt.stat().st_mtime if receipt.exists() else time.time()) - 1200
    candidates = []
    manifest = {'copied': [], 'skipped': [], 'cutoffEpoch': cutoff}
    searches = [(results / 'work', ('hs_err_pid*.log', 'replay_pid*.log'), True)]
    searches += [(results / 'jvm-crash-inputs', ('hs_err_pid*.log', 'replay_pid*.log'), False)]
    searches += [(root, ('java*.ips', 'java*.crash', 'sbt*.ips', 'sbt*.crash'), False)
                 for root in diagnostic_roots]
    for root, patterns, recursive in searches:
        for pattern in patterns:
            try:
                paths = root.rglob(pattern) if recursive else root.glob(pattern)
                for path in paths:
                    if not path.is_symlink() and path.is_file() and path.stat().st_mtime >= cutoff:
                        candidates.append(path)
            except OSError as error:
                manifest['skipped'].append({'path': str(root), 'reason': str(error)})
    total = 0
    for path in sorted(set(candidates), key=lambda p: p.stat().st_mtime, reverse=True):
        size = path.stat().st_size
        if size > 2 * 1024 * 1024 or total + size > 8 * 1024 * 1024 or len(manifest['copied']) >= 10:
            manifest['skipped'].append({'path': str(path), 'reason': 'diagnostic size/count budget'})
            continue
        with path.open('rb') as report:
            data = report.read(2 * 1024 * 1024 + 1)
        if len(data) > 2 * 1024 * 1024 or total + len(data) > 8 * 1024 * 1024:
            manifest['skipped'].append({'path': str(path), 'reason': 'report grew beyond diagnostic budget'})
            continue
        target = destination / (str(len(manifest['copied'])) + '-' + path.name)
        target.write_bytes(data)
        total += len(data)
        manifest['copied'].append({'source': str(path), 'file': target.name,
                                   'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    (results / 'crash-reports.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', required=True, type=Path)
    args = parser.parse_args()
    roots = []
    if platform.system() == 'Darwin':
        roots = [Path.home() / 'Library/Logs/DiagnosticReports', Path('/Library/Logs/DiagnosticReports')]
    # CrashReporter can finish writing its report after the process exits.
    for attempt in range(6):
        manifest = collect(args.results, roots)
        if manifest['copied'] or not roots or attempt == 5:
            break
        time.sleep(1)
    print(json.dumps(manifest, indent=2))
