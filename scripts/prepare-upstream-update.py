#!/usr/bin/env python3
"""Build an isolated update proposal. Never changes the shipping lock files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ref', required=True, help='Full upstream commit SHA')
parser.add_argument('--build', action='store_true', help='Also run frozen install, artifact gates and type checks')
parser.add_argument('--archive', type=Path, help='Use a previously downloaded source archive')
parser.add_argument('--candidate-root', type=Path, help='Candidate storage; use a Linux filesystem when running under WSL')
args = parser.parse_args()
if not re.fullmatch(r'[0-9a-f]{40}', args.ref):
    parser.error('--ref must be a full lowercase SHA')
project = Path(__file__).resolve().parent.parent
candidate_root = args.candidate_root or project / 'runtime/candidates'
candidate_root.mkdir(parents=True, exist_ok=True)
candidate = Path(tempfile.mkdtemp(prefix=args.ref[:12] + '-', dir=candidate_root))
url = f'https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/{args.ref}'
archive = candidate / 'upstream.tar.gz'
if args.archive:
    archive.write_bytes(args.archive.read_bytes())
else:
    with urlopen(Request(url, headers={'User-Agent': 'DSH-Android-upstream-check'}), timeout=60) as source, archive.open('wb') as target:
        total = 0
        while chunk := source.read(1024 * 1024):
            total += len(chunk)
            if total > 128 * 1024 * 1024:
                raise RuntimeError('Unexpected upstream archive size')
            target.write(chunk)
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
print('Archive downloaded; validating source and extracting candidate', flush=True)
shipping = json.loads((project / 'upstream.lock.json').read_text())
sources = json.loads((project / 'sources.lock.json').read_text())
if args.ref == shipping['commit'] and digest != sources['upstream.tar.gz']['sha256']:
    raise RuntimeError('Pinned source archive integrity check failed')
with tarfile.open(archive) as source:
    members = source.getmembers()
    prefix = f'deepseek-harness-{args.ref}'
    if any(not (member.name == prefix or member.name.startswith(prefix + '/')) for member in members):
        raise RuntimeError('Archive does not match the requested commit')
    source.extractall(candidate, filter='data')
upstream = candidate / prefix
print('Applying Android patch contracts', flush=True)
subprocess.run(['node', str(project / 'scripts/patch-upstream.mjs'), str(upstream)], check=True)
cli = next((json.loads(path.read_text()) for path in upstream.rglob('package.json')
            if 'node_modules' not in path.parts and json.loads(path.read_text()).get('name') == '@deepseek-ai/dsh'), None)
if cli is None:
    raise RuntimeError('Upstream CLI package contract changed')
shipping.update(commit=args.ref, dshVersion=cli['version'])
sources['upstream.tar.gz'] = {'url': url, 'sha256': digest}
proposal = candidate / 'proposed-locks'
proposal.mkdir()
(proposal / 'upstream.lock.json').write_text(json.dumps(shipping, indent=2) + '\n')
(proposal / 'sources.lock.json').write_text(json.dumps(sources, indent=2) + '\n')
if args.build:
    environment = {**os.environ, 'DSH_CLIENT_COMMIT_HASH': args.ref}
    for command in (['pnpm', 'install', '--frozen-lockfile'], ['pnpm', 'run', 'build:artifacts'], ['pnpm', 'run', 'build:check']):
        subprocess.run(command, cwd=upstream, env=environment, check=True)
report = {'commit': args.ref, 'version': cli['version'], 'engines': cli.get('engines'),
          'archiveSha256': digest, 'patchContracts': 'passed', 'hostBuild': 'passed' if args.build else 'not-run',
          'androidRuntime': 'not-run', 'locksPromoted': False}
(candidate / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
print(f'Proposal: {proposal}. Review and Android device tests are still required.')
