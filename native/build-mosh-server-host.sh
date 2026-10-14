#!/bin/sh
# Build a fully static x86_64 mosh-server 1.4.0 (musl) for Linux hosts whose packaged mosh is
# older. 1.3.2 (Ubuntu 20.04) predates truecolor, so 24-bit colors arrive mangled.
#
#   docker run --rm --memory 4g -v ~/code/pocketshell:/work alpine:3.20 sh /work/native/build-mosh-server-host.sh
#
# Output: native/out/mosh-server-1.4.0-x86_64. Sources and intermediates go to native/build-host/.
set -eu

MOSH_VERSION=1.4.0
PROTOBUF_VERSION=21.12          # protobuf-cpp 3.21.12, the last release without abseil
NCURSES_VERSION=6.4

ROOT=/work/native
BUILD=$ROOT/build-host
SRC=$BUILD/src
PREFIX=$BUILD/prefix
OUT=$ROOT/out
JOBS=4

apk add --no-cache build-base autoconf automake libtool pkgconf curl perl linux-headers \
    openssl-dev openssl-libs-static zlib-dev zlib-static \
    ncurses >/dev/null   # tic, which ncurses' install step runs

mkdir -p "$SRC" "$PREFIX" "$OUT"
fetch() { # url dir
    [ -d "$SRC/$2" ] || curl -fsSL "$1" | tar -xz -C "$SRC"
}

if [ ! -f "$BUILD/.done-protobuf" ]; then
    fetch https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protobuf-cpp-3.$PROTOBUF_VERSION.tar.gz protobuf-3.$PROTOBUF_VERSION
    cd "$SRC/protobuf-3.$PROTOBUF_VERSION"
    ./configure --prefix="$PREFIX" --disable-shared --enable-static >/dev/null
    make -j$JOBS >/dev/null && make install >/dev/null
    touch "$BUILD/.done-protobuf"
fi

if [ ! -f "$BUILD/.done-ncurses" ]; then
    fetch https://ftp.gnu.org/gnu/ncurses/ncurses-$NCURSES_VERSION.tar.gz ncurses-$NCURSES_VERSION
    cd "$SRC/ncurses-$NCURSES_VERSION"
    # mosh's configure insists on curses, but mosh-server never calls setupterm (only the client's
    # Display does), so the terminfo database is never read at runtime. The search path points at
    # the usual system locations anyway.
    ./configure --prefix="$PREFIX" --without-shared --with-normal --without-debug --without-ada \
        --without-cxx-binding --without-manpages --without-progs --without-tests --enable-widec \
        --with-terminfo-dirs=/etc/terminfo:/lib/terminfo:/usr/share/terminfo \
        --with-default-terminfo-dir=/usr/share/terminfo >/dev/null
    make -j$JOBS >/dev/null && make install >/dev/null
    touch "$BUILD/.done-ncurses"
fi

fetch https://github.com/mobile-shell/mosh/releases/download/mosh-$MOSH_VERSION/mosh-$MOSH_VERSION.tar.gz mosh-$MOSH_VERSION
cd "$SRC/mosh-$MOSH_VERSION"
make distclean >/dev/null 2>&1 || true
PATH="$PREFIX/bin:$PATH" \
PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
protobuf_CFLAGS="-I$PREFIX/include" protobuf_LIBS="-L$PREFIX/lib -lprotobuf" \
TINFO_CFLAGS="-I$PREFIX/include -I$PREFIX/include/ncursesw" TINFO_LIBS="-L$PREFIX/lib -lncursesw" \
CPPFLAGS="-I$PREFIX/include -I$PREFIX/include/ncursesw" \
LDFLAGS="-static -L$PREFIX/lib" \
./configure --enable-server --disable-client --disable-completion --without-utempter \
    --with-crypto-library=openssl >/dev/null
make -j$JOBS >/dev/null
strip src/frontend/mosh-server
cp src/frontend/mosh-server "$OUT/mosh-server-$MOSH_VERSION-x86_64"
[ -n "${HOST_UID:-}" ] && chown -R "$HOST_UID:${HOST_GID:-$HOST_UID}" "$BUILD" "$OUT"
echo "built $OUT/mosh-server-$MOSH_VERSION-x86_64"
