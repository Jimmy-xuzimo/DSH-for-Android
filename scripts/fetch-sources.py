#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument('destination', type=Path)
args = p.parse_args()
lock = json.loads((Path(__file__).resolve().parent.parent / 'sources.lock.json').read_text())
args.destination.mkdir(parents=True, exist_ok=True)
for name, source in lock.items():
    target = args.destination / name
    if not target.exists():
        partial = target.with_suffix(target.suffix + '.part')
        subprocess.run(['curl', '--fail', '--location', '--retry', '5', '--retry-all-errors',
                        '--connect-timeout', '20', '--max-time', '900', '--output', str(partial), source['url']], check=True)
        partial.rename(target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != source['sha256']:
        raise SystemExit('Source checksum mismatch: ' + str(target))
    print('Verified ' + name)
