#!/usr/bin/env bash
# Cross-compile a static arm64 Android mosh-client for pocketshell.
#
# Runs inside the pocketshell-toolchain image with the repo mounted at /work:
#   docker run --rm --memory 6g -v ~/code/pocketshell:/work pocketshell-toolchain bash native/build-mosh.sh
#
# Sources and intermediate installs go to /work/native/build (gitignored); each
# dependency is stamped so a re-run only redoes what's missing. Delete
# native/build to start clean.
set -euo pipefail

PROTOBUF_VERSION=21.12        # protobuf-cpp 3.21.12, the last release without abseil
OPENSSL_VERSION=3.0.15
NCURSES_VERSION=6.4
MOSH_VERSION=1.4.0
API=24
JOBS=${JOBS:-4}

ROOT=/work/native/build
SRC=$ROOT/src
PREFIX=$ROOT/prefix
OUT=/work/app/src/main/jniLibs/arm64-v8a/libmoshclient.so

TC=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
HOST=aarch64-linux-android
export PATH=$TC/bin:$PATH
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
export CC=$TC/bin/$HOST$API-clang
export CXX=$TC/bin/$HOST$API-clang++
export AR=$TC/bin/llvm-ar
export RANLIB=$TC/bin/llvm-ranlib
export STRIP=$TC/bin/llvm-strip
export CFLAGS="-O2 -fPIC"
export CXXFLAGS="-O2 -fPIC"
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig

mkdir -p "$SRC" "$PREFIX"

fetch() { # url dir
  local url=$1 dir=$2
  if [ ! -d "$SRC/$dir" ]; then
    echo ">> fetching $url"
    curl -fsSL "$url" | tar -xz -C "$SRC"
  fi
}

done_stamp() { [ -f "$ROOT/.done-$1" ]; }
stamp() { touch "$ROOT/.done-$1"; }

# --- protobuf ---------------------------------------------------------------
if ! done_stamp protobuf; then
  fetch https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protobuf-cpp-3.$PROTOBUF_VERSION.tar.gz protobuf-3.$PROTOBUF_VERSION
  cd "$SRC/protobuf-3.$PROTOBUF_VERSION"
  ./configure --host=$HOST --prefix="$PREFIX" --disable-shared --enable-static \
    --with-protoc=/opt/protoc/bin/protoc LIBS="-llog"
  make -j"$JOBS"
  make install
  stamp protobuf
fi

# --- OpenSSL ----------------------------------------------------------------
if ! done_stamp openssl; then
  fetch https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz openssl-$OPENSSL_VERSION
  cd "$SRC/openssl-$OPENSSL_VERSION"
  # OpenSSL's android targets pick their own compiler from PATH + the NDK.
  env -u CC -u CXX -u AR -u RANLIB -u CFLAGS -u CXXFLAGS \
    ./Configure android-arm64 -D__ANDROID_API__=$API no-shared no-tests \
      --prefix="$PREFIX" --libdir=lib
  make -j"$JOBS" build_libs
  make install_dev
  stamp openssl
fi

# --- ncurses (terminfo for xterm-256color compiled in, no database) ---------
if ! done_stamp ncurses; then
  fetch https://ftp.gnu.org/gnu/ncurses/ncurses-$NCURSES_VERSION.tar.gz ncurses-$NCURSES_VERSION
  cd "$SRC/ncurses-$NCURSES_VERSION"
  ./configure --host=$HOST --prefix="$PREFIX" --with-build-cc=gcc \
    --without-shared --with-normal --without-debug --without-cxx-binding --without-ada \
    --without-progs --without-tests --without-manpages --disable-stripping \
    --enable-widec --disable-database --with-fallbacks=xterm-256color \
    --enable-pc-files --with-pkg-config-libdir="$PREFIX/lib/pkgconfig"
  make -j"$JOBS"
  make install
  stamp ncurses
fi

# --- mosh -------------------------------------------------------------------
if ! done_stamp mosh; then
  fetch https://github.com/mobile-shell/mosh/releases/download/mosh-$MOSH_VERSION/mosh-$MOSH_VERSION.tar.gz mosh-$MOSH_VERSION
  cd "$SRC/mosh-$MOSH_VERSION"
  # Bionic only gained nl_langinfo() at API 26. On Android the C library's
  # multibyte charset is UTF-8 exactly when MB_CUR_MAX > 1, which is what
  # nl_langinfo(CODESET) itself reports from API 26 on.
  if ! grep -q POCKETSHELL src/util/locale_utils.cc; then
    sed -i 's|  const char \*ret = nl_langinfo( CODESET );|#ifdef __ANDROID__ /* POCKETSHELL */\n  const char *ret = MB_CUR_MAX > 1 ? "UTF-8" : ASCII_name;\n#else\n  const char *ret = nl_langinfo( CODESET );\n#endif|' src/util/locale_utils.cc
  fi
  ./configure --host=$HOST --prefix="$PREFIX" \
    --enable-client --disable-server --disable-completion --with-crypto-library=openssl \
    PROTOC=/opt/protoc/bin/protoc \
    protobuf_CFLAGS="-I$PREFIX/include" protobuf_LIBS="-L$PREFIX/lib -lprotobuf-lite" \
    CPPFLAGS="-I$PREFIX/include -I$PREFIX/include/ncursesw" \
    CXXFLAGS="$CXXFLAGS -std=c++17" \
    LDFLAGS="-L$PREFIX/lib -static-libstdc++" \
    LIBS="-llog"
  make -j"$JOBS"
  stamp mosh
fi

mkdir -p "$(dirname "$OUT")"
cp "$SRC/mosh-$MOSH_VERSION/src/frontend/mosh-client" "$OUT"
$STRIP "$OUT"
file "$OUT"
$TC/bin/llvm-readelf -d "$OUT" | grep NEEDED
ls -l "$OUT"
