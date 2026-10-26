#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import shutil

p = argparse.ArgumentParser()
p.add_argument('upstream', type=Path)
p.add_argument('node_source', type=Path)
p.add_argument('tools_build', type=Path)
p.add_argument('staging', type=Path)
p.add_argument('project', type=Path)
a = p.parse_args()
inventory = json.loads((a.staging / 'package-inventory.json').read_text())
licenses = a.project / 'licenses'
licenses.mkdir(parents=True, exist_ok=True)
sources = {
    'deepseek-harness': a.upstream / 'LICENSE',
    'node': a.node_source / 'LICENSE',
    'bash': a.tools_build / 'bash-5.3/COPYING',
    'git': a.tools_build / 'git-2.51.2/COPYING',
    'openssl': a.tools_build / 'openssl-3.5.4/LICENSE.txt',
    'curl': a.tools_build / 'curl-8.16.0/COPYING',
    'zlib': a.tools_build / 'zlib-1.3.1/LICENSE',
}
for name, source in sources.items():
    if not source.is_file():
        raise SystemExit('Missing source license: ' + str(source))
    shutil.copyfile(source, licenses / (name + '.txt'))
text = '''# Third-party notices

The Android carrier is original integration code maintained by Jimmy-xuzimo.
DeepSeek Harness and every bundled dependency retain their original copyright
and license. GitHub contributor statistics do not replace these attributions.

## Source-built native components

| Component | Version | License |
| --- | --- | --- |
| DeepSeek Harness | 0.2.1-rc.3, commit 22ddee3792402c3e70ec5f86a63c3e3e895a2f60 | MIT |
| Node.js and its bundled dependencies | 24.21.0 | MIT and bundled notices |
| GNU Bash | 5.3 | GPL-3.0-or-later |
| Git | 2.51.2 | GPL-2.0-only and bundled notices |
| curl | 8.16.0 | curl |
| OpenSSL | 3.5.4 | Apache-2.0 |
| zlib | 1.3.1 | Zlib |
| node-pty | 1.2.0-beta.15 | MIT |
| ripgrep | 14.1.1 | MIT / Unlicense; Rust crate notices in corresponding source |
| Android NDK cpufeatures | NDK 28.2.13676358 | BSD-style Android source notice |

Full principal license texts are in `licenses/`. The Node source license
contains additional embedded dependency notices. Runtime npm packages retain
their shipped license files inside `runtime.zip`; the table below records
their manifest license declarations. These declarations are not a relicensing.

The signed APK is distributed as an aggregate containing independently licensed
programs. Corresponding source for GPL components, exact build scripts and
Android patches must accompany binary releases. The source archive includes
the checksum-pinned native sources, Android NDK cpufeatures files, and this
repository's integration sources. See `sources.lock.json` for source identities.

## Android and runtime library notices

AndroidX libraries and Kotlin standard libraries retain their Apache-2.0
licenses. Exact resolved versions, including transitive libraries, are in
`app/gradle.lockfile` and `docs/evidence/android-dependencies.txt`.
The Gradle Wrapper is Apache-2.0 licensed build tooling. The Android framework
itself is provided by the device, not redistributed inside this APK.

`licenses/Apache-2.0.txt`, `licenses/ndk-runtime-NOTICE.txt` and
`licenses/rust-runtime-COPYRIGHT.txt` preserve the Android/LLVM/Rust runtime
license material. Android cpufeatures source notices are in `licenses/source/`.
The corresponding-source release archive also contains the pinned ripgrep
source and vendored Rust crates with their individual license texts.

## Runtime JavaScript package inventory

| Package | Version | Declared license |
| --- | --- | --- |
'''
seen = set()
for item in sorted(inventory, key=lambda v: (v['name'], v['version'])):
    pair = (item['name'], item['version'])
    if pair in seen:
        continue
    seen.add(pair)
    license_value = item.get('license', 'See bundled license')
    if not isinstance(license_value, str):
        license_value = json.dumps(license_value)
    text += f"| {item['name']} | {item['version']} | {license_value.replace('|', '/')} |\n"
(a.project / 'THIRD_PARTY_NOTICES.md').write_text(text)
print(f'Wrote license texts and {len(seen)} package notices')
