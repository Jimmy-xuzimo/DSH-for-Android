#!/usr/bin/env python3
"""Create release source and GPL corresponding-source aggregates; never include keys."""
import argparse
import json
from pathlib import Path
import tarfile

p = argparse.ArgumentParser()
p.add_argument('destination', type=Path)
p.add_argument('--downloads', type=Path)
p.add_argument('--rust-vendor', type=Path)
a = p.parse_args()
root = Path(__file__).resolve().parent.parent
a.destination.mkdir(parents=True, exist_ok=True)
excluded = {'.git', '.gradle', 'build', 'downloads', 'staging', 'jniLibs', 'search'}
def sources():
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if any(part in excluded for part in relative.parts) or not path.is_file(): continue
        if relative.as_posix() in {'app/src/main/assets/runtime.zip', 'app/src/main/assets/runtime-manifest.json'}: continue
        if path.suffix.lower() in {'.apk', '.p12', '.jks', '.keystore', '.log'} or path.name == 'local.properties': continue
        yield path, Path('dsh-android') / relative
with tarfile.open(a.destination / 'dsh-android-source.tar.gz', 'w:gz') as archive:
    for path, name in sources(): archive.add(path, arcname=str(name), recursive=False)
if a.downloads:
    lock = json.loads((root / 'sources.lock.json').read_text(encoding='utf-8-sig'))
    import hashlib
    for name, record in lock.items():
        data = (a.downloads / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != record['sha256']: raise SystemExit('Source digest mismatch: ' + name)
    with tarfile.open(a.destination / 'dsh-android-corresponding-source.tar.gz', 'w:gz') as archive:
        for path, name in sources(): archive.add(path, arcname=str(name), recursive=False)
        for name in lock: archive.add(a.downloads / name, arcname='native-sources/' + name, recursive=False)
        # NDK cpufeatures source and license are copied by the native build.
        extra = root / 'licenses/source'
        if extra.exists(): archive.add(extra, arcname='native-sources/android-cpufeatures')
        if a.rust_vendor: archive.add(a.rust_vendor, arcname='native-sources/ripgrep-cargo-vendor')
print('Created source archives; signing material and generated runtime code are excluded.')
