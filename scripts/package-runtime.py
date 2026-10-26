#!/usr/bin/env python3
"""Package checksummed JS/data and separate APK-native code, without following symlinks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile

p = argparse.ArgumentParser()
p.add_argument('staging', type=Path)
p.add_argument('native', type=Path)
p.add_argument('project', type=Path)
p.add_argument('--abi', action='append', help='Package only this ABI for local device tests')
p.add_argument('--node-source', type=Path, required=True, help='Pinned Node source containing the bundled npm closure')
args = p.parse_args()
stage = args.staging.resolve()
project = args.project.resolve()
lock = json.loads((project / 'upstream.lock.json').read_text())
inventory = json.loads((stage / 'package-inventory.json').read_text())
shutil.copytree(project / 'runtime/android', stage / 'android', dirs_exist_ok=True)
shutil.copytree(args.node_source / 'deps/npm', stage / 'npm', dirs_exist_ok=True, symlinks=False)
commands = {'node': 'libdsh_node.so', 'bash': 'libdsh_bash.so', 'git': 'libdsh_git.so', 'rg': 'libdsh_rg.so',
            'git-remote-http': 'libdsh_git_remote_http.so', 'git-remote-https': 'libdsh_git_remote_http.so',
            'curl': 'libdsh_curl.so', 'pnpm': 'libdsh_pnpm.so', 'npm': 'libdsh_npm.so', 'npx': 'libdsh_npx.so'}
addons = {'android/android-posix.node': 'libdsh_posix.so'}
(stage / 'android/android-posix.node').write_bytes(b'')
for package in inventory:
    if package['name'] == 'node-pty':
        path = stage / package['path'] / 'build/Release/pty.node'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'')
        addons[path.relative_to(stage).as_posix()] = 'libdsh_pty.so'
    if package['name'] == '@deepseek-ai/node-addon-system':
        addons[package['path'] + '/lib/android-system.node'] = 'libdsh_flock.so'
(stage / 'android/native-map.json').write_text(json.dumps({'commands': commands, 'addons': addons}))
assets = project / 'app/src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
files, links, total = {}, {}, 0
with zipfile.ZipFile(assets / 'runtime.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for directory, directories, names in os.walk(stage, followlinks=False):
        for name in list(directories) + names:
            path = Path(directory) / name
            relative = path.relative_to(stage).as_posix()
            if path.is_symlink():
                target = os.readlink(path)
                if not path.resolve().is_relative_to(stage):
                    raise ValueError('Runtime symlink escapes staging: ' + relative)
                links[relative] = target
                continue
            if not path.is_file():
                continue
            data = path.read_bytes()
            if data.startswith(b'\x7fELF'):
                raise ValueError('ELF found in writable runtime data: ' + relative)
            files[relative] = hashlib.sha256(data).hexdigest()
            total += len(data)
            archive.write(path, relative)
manifest = {'format': 1, 'upstreamCommit': lock['commit'], 'files': files, 'links': links, 'unpackedBytes': total}
(assets / 'runtime-manifest.json').write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')))
for abi in (args.abi or lock['abis']):
    source = args.native / abi
    if not source.is_dir():
        raise ValueError('Missing ABI: ' + abi)
    required = set(commands.values()) | set(addons.values()) | {'libc++_shared.so'}
    for name in required:
        path = source / name
        if not path.is_file() or not path.read_bytes().startswith(b'\x7fELF'):
            raise ValueError('Missing Android native artifact: ' + str(path))
    destination = project / 'app/src/main/jniLibs' / abi
    destination.mkdir(parents=True, exist_ok=True)
    for name in required:
        shutil.copy2(source / name, destination / name)
print(f'Packaged {len(files)} data files, {len(links)} relative links, {total} unpacked bytes')
