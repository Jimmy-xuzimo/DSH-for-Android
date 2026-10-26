#!/usr/bin/env bash
set -euo pipefail
abi=${1:?ABI}; ndk=$(realpath "${2:?NDK}"); output=$(realpath -m "${3:?output}")
sources=$(realpath "${4:?source archives directory}")
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android ;;
  x86_64) triple=x86_64-linux-android ;;
  *) exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$toolchain/bin:/usr/local/bin:/usr/bin:/bin"
export CC="${triple}29-clang" CXX="${triple}29-clang++" AR=llvm-ar RANLIB=llvm-ranlib
export CFLAGS="-O2 -fPIC" LDFLAGS="-Wl,-z,max-page-size=16384"
build=$(realpath -m "${DSH_NATIVE_BUILD:-runtime/build}/$abi/tools")
prefix="$build/prefix"
mkdir -p "$build" "$prefix" "$output"
cd "$build"
tar -xf "$sources/bash-5.3.tar.gz"
cd bash-5.3
ac_cv_func_mmap_fixed_mapped=yes bash_cv_dev_fd=standard bash_cv_getcwd_malloc=yes \
  ./configure --host="$triple" --prefix="$prefix" --without-bash-malloc --disable-nls \
  --disable-progcomp --disable-net-redirections
make -j"${DSH_BUILD_JOBS:-8}"
install -m755 bash "$output/libdsh_bash.so"
cd "$build"
tar -xf "$sources/zlib-1.3.1.tar.gz"
cd zlib-1.3.1
CHOST="$triple" ./configure --static --prefix="$prefix"
make -j"${DSH_BUILD_JOBS:-8}"; make install
cd "$build"
mkdir -p openssl-3.5.4
tar -xf "$sources/openssl-3.5.4.tar.gz" --strip-components=1 -C openssl-3.5.4
cd openssl-3.5.4
case "$abi" in arm64-v8a) openssl_target=android-arm64 ;; x86_64) openssl_target=android-x86_64 ;; esac
ANDROID_NDK_ROOT="$ndk" ./Configure "$openssl_target" -D__ANDROID_API__=29 no-shared no-tests --prefix="$prefix"
make -j"${DSH_BUILD_JOBS:-8}"; make install_sw
cd "$build"
tar -xf "$sources/curl-8.16.0.tar.xz"
cd curl-8.16.0
./configure --host="$triple" --prefix="$prefix" --disable-shared --enable-static \
  --with-openssl="$prefix" --with-zlib="$prefix" --without-libpsl --without-libidn2 \
  --without-brotli --without-zstd --without-nghttp2 --disable-ldap --disable-ldaps
make -j"${DSH_BUILD_JOBS:-8}"; make install
install -m755 src/curl "$output/libdsh_curl.so"
cd "$build"
tar -xf "$sources/git-2.51.2.tar.xz"
cd git-2.51.2
if ! grep -q DSH_ANDROID_SHELL git-compat-util.h; then
  cat >> git-compat-util.h <<'HEADER'
/* DSH_ANDROID_SHELL: build scripts use host sh; target processes use Android sh. */
#if defined(__ANDROID__)
#undef SHELL_PATH
#define SHELL_PATH "/system/bin/sh"
#endif
HEADER
fi
make -j"${DSH_BUILD_JOBS:-8}" CC="$CC" AR="$AR" \
  NO_GETTEXT=YesPlease NO_TCLTK=YesPlease NO_PERL=YesPlease NO_PYTHON=YesPlease \
  NO_PTHREADS=YesPlease SHELL=/bin/sh SHELL_PATH=/bin/sh \
  NO_EXPAT=YesPlease NO_REGEX=YesPlease NO_ICONV=YesPlease NO_UNCOMPRESS2=YesPlease \
  CURLDIR="$prefix" OPENSSLDIR="$prefix" ZLIB_PATH="$prefix" \
  CURL_LDFLAGS="$prefix/lib/libcurl.a $prefix/lib/libssl.a $prefix/lib/libcrypto.a $prefix/lib/libz.a" \
  CFLAGS="$CFLAGS -DNO_SYS_POLL_H" LDFLAGS="$LDFLAGS" git git-remote-http
install -m755 git "$output/libdsh_git.so"
install -m755 git-remote-http "$output/libdsh_git_remote_http.so"
for binary in "$output"/libdsh_*.so; do "$toolchain/bin/llvm-strip" --strip-debug "$binary"; done
