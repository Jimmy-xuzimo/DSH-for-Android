#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
build=$(realpath -m "${DSH_BUILD_ROOT:-$root/runtime/build}")
downloads="$root/runtime/downloads"
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 28.2.13676358 on Linux}
grep -Fq 'Pkg.Revision = 28.2.13676358' "$ndk/source.properties" || { echo 'Wrong Android NDK version' >&2; exit 1; }
mkdir -p "$build"
python3 "$root/scripts/fetch-sources.py" "$downloads"
if [ ! -d "$build/upstream" ]; then
  mkdir -p "$build/upstream"
  tar -xzf "$downloads/upstream.tar.gz" --strip-components=1 -C "$build/upstream"
fi
node "$root/scripts/patch-upstream.mjs" "$build/upstream"
export DSH_CLIENT_COMMIT_HASH=22ddee3792402c3e70ec5f86a63c3e3e895a2f60
cd "$build/upstream"
pnpm install --frozen-lockfile
pnpm run build:artifacts
pnpm run build:check
for abi in x86_64 arm64-v8a; do
  source="$build/node-$abi"
  if [ ! -d "$source" ]; then mkdir -p "$source"; tar -xJf "$downloads/node-v24.21.0.tar.xz" --strip-components=1 -C "$source"; fi
  bash "$root/scripts/build-node.sh" "$abi" "$source" "$ndk" "$build/native/$abi"
  DSH_NATIVE_BUILD="$build/tools" bash "$root/scripts/build-tools.sh" "$abi" "$ndk" "$build/native/$abi" "$downloads"
  bash "$root/scripts/build-addons.sh" "$abi" "$ndk" "$source" "$build/upstream" "$build/native/$abi"
  DSH_SEARCH_BUILD="$build/search" bash "$root/scripts/build-search.sh" "$abi" "$ndk" "$build/native/$abi" "$downloads"
done
staging="$build/staging-$(date +%s)-$$"
node "$root/scripts/stage-runtime.mjs" "$build/upstream" "$staging"
mkdir -p "$staging/android"
cp "$downloads/cacert.pem" "$staging/android/cacert.pem"
python3 "$root/scripts/package-runtime.py" "$staging" "$build/native" "$root" --node-source "$source"
python3 "$root/scripts/audit-native.py" "$root/app/src/main/jniLibs"
python3 "$root/scripts/third-party-notices.py" "$build/upstream" "$source" "$build/tools/x86_64/tools" "$staging" "$root"
