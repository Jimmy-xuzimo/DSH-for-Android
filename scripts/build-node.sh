#!/usr/bin/env bash
set -euo pipefail
abi=${1:?usage: build-node.sh arm64-v8a|x86_64 SOURCE NDK OUTPUT}
source_dir=$(realpath "${2:?Node source directory}")
ndk=$(realpath "${3:?Linux NDK directory}")
output=$(realpath -m "${4:?output directory}")
case "$abi" in
  arm64-v8a) arch=arm64; triple=aarch64-linux-android ;;
  x86_64) arch=x64; triple=x86_64-linux-android ;;
  *) echo "Unsupported ABI" >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$toolchain/bin:/usr/local/bin:/usr/bin:/bin"
export CC="$toolchain/bin/${triple}29-clang"
export CXX="$toolchain/bin/${triple}29-clang++"
export AR="$toolchain/bin/llvm-ar"
export CC_host=gcc CXX_host=g++
export LDFLAGS="-Wl,-z,max-page-size=16384"
export GYP_DEFINES="target_arch=$arch v8_target_arch=$arch android_target_arch=$arch host_os=linux OS=android android_ndk_path=$ndk"
mkdir -p "$output"
cd "$source_dir"
python3 - "$ndk" <<'PY'
from pathlib import Path
import sys
path = Path('deps/zlib/zlib.gyp')
source = path.read_text()
feature = Path(sys.argv[1]) / 'sources/android/cpufeatures/cpu-features.c'
Path('deps/zlib/dsh-android-cpu-features.c').write_bytes(feature.read_bytes())
source = source.replace(sys.argv[1] + '/sources/android/cpufeatures/cpu-features.c', 'dsh-android-cpu-features.c')
marker = "'target_name': 'zlib',"
addition = "'sources': ['dsh-android-cpu-features.c'],"
if 'DSH_ANDROID_CPUFEATURES' not in source:
    if marker not in source:
        raise SystemExit('Node zlib build contract changed')
    start = source.index(marker)
    index = source.index("'conditions': [", start) + len("'conditions': [")
    source = source[:index] + "\n      # DSH_ANDROID_CPUFEATURES\n      ['OS==\"android\" and _toolset==\"target\"', {" + addition + "}]," + source[index:]
path.write_text(source)
# Node's GYP port selects sources using target OS even for Linux host tools.
# Keep Android's V8 trap handler disabled; supply POSIX handlers to host tools only.
path = Path('tools/v8_gypfiles/v8.gyp')
source = path.read_text()
import re
source = re.sub(r'OS in "(linux[^"\n]*)"',
    lambda match: '(OS in "' + match.group(1) + '" or (OS=="android" and _toolset=="host" and host_os=="linux"))',
    source) if 'DSH_ANDROID_HOST_TRAPS' not in source else source
if 'DSH_ANDROID_HOST_TRAPS' not in source:
    source = '# DSH_ANDROID_HOST_TRAPS\n' + source
path.write_text(source)
# Keep diagnostics in the carrier-owned, redacted pipe instead of public logcat.
path = Path('src/debug_utils.cc')
source = path.read_text().replace('#elif defined(__ANDROID__)\n', '#elif defined(__ANDROID__) && 0 /* DSH_ANDROID_STDIO */\n')
path.write_text(source)
PY
./configure --dest-cpu="$arch" --dest-os=android --cross-compiling --openssl-no-asm \
  --without-node-snapshot --without-node-code-cache --with-intl=small-icu
make -j"${DSH_BUILD_JOBS:-8}"
install -m755 out/Release/node "$output/libdsh_node.so"
"$toolchain/bin/llvm-strip" --strip-debug "$output/libdsh_node.so"
"$toolchain/bin/llvm-readelf" -l "$output/libdsh_node.so" > "$output/node-program-headers.txt"
