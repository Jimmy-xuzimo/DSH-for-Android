#!/usr/bin/env bash
set -euo pipefail
abi=${1:?ABI}; ndk=$(realpath "${2:?NDK}"); output=$(realpath -m "${3:?output}")
sources=$(realpath "${4:?source archives directory}")
case "$abi" in
  arm64-v8a) target=aarch64-linux-android; triple=aarch64-linux-android ;;
  x86_64) target=x86_64-linux-android; triple=x86_64-linux-android ;;
  *) exit 1 ;;
esac
build=$(realpath -m "${DSH_SEARCH_BUILD:-runtime/search}/$abi")
mkdir -p "$build" "$output"
tar -xzf "$sources/ripgrep-14.1.1.tar.gz" --strip-components=1 -C "$build"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$toolchain:$HOME/.cargo/bin:/usr/local/bin:/usr/bin:/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android29-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$toolchain/x86_64-linux-android29-clang"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
cd "$build"
cargo +1.85.1 build --locked --release --target "$target" --no-default-features
install -m755 "target/$target/release/rg" "$output/libdsh_rg.so"
"$toolchain/llvm-strip" --strip-debug "$output/libdsh_rg.so"
