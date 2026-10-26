#!/usr/bin/env bash
set -euo pipefail
abi=${1:?ABI}; ndk=$(realpath "${2:?NDK}"); node_src=$(realpath "${3:?Node source}")
upstream=$(realpath "${4:?upstream}"); output=$(realpath -m "${5:?output}")
case "$abi" in arm64-v8a) triple=aarch64-linux-android ;; x86_64) triple=x86_64-linux-android ;; *) exit 1 ;; esac
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
mkdir -p "$output"
includes=("-I$node_src/src" "-I$node_src/deps/v8/include" "-I$node_src/deps/uv/include")
"$toolchain/bin/${triple}29-clang" -shared -fPIC -O2 -DNAPI_VERSION=8 \
  "${includes[@]}" -Wl,-z,max-page-size=16384 \
  "$upstream/native/system/packages/entry/src/flock.c" -o "$output/libdsh_flock.so"
pty=$(find "$upstream/node_modules/.pnpm" -path '*/node_modules/node-pty/src/unix/pty.cc' -print -quit)
napi=$(find "$upstream/node_modules/.pnpm" -path '*/node_modules/node-addon-api/napi.h' -print -quit)
test -n "$pty" && test -n "$napi"
"$toolchain/bin/${triple}29-clang++" -shared -fPIC -O2 -std=c++20 -DNODE_GYP_MODULE_NAME=pty \
  "${includes[@]}" "-I$(dirname "$napi")" -DNAPI_CPP_EXCEPTIONS -Wl,-z,max-page-size=16384 \
  "$pty" -o "$output/libdsh_pty.so"
cp "$toolchain/sysroot/usr/lib/$triple/libc++_shared.so" "$output/libc++_shared.so"
port=$(cd "$(dirname "$0")/.." && pwd)
"$toolchain/bin/${triple}29-clang" -shared -fPIC -O2 -DNAPI_VERSION=8 -DNODE_GYP_MODULE_NAME=android_posix \
  "${includes[@]}" -Wl,-z,max-page-size=16384 \
  "$port/runtime/native/atomic-publish.c" -o "$output/libdsh_posix.so"
for command in pnpm npm npx; do
  "$toolchain/bin/${triple}29-clang" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
    "$port/runtime/native/js-launcher.c" -o "$output/libdsh_$command.so"
done
