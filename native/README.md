# native: mosh-client for Android

`build-mosh.sh` cross-compiles a static arm64 `mosh-client` and installs it as
`app/src/main/jniLibs/arm64-v8a/libmoshclient.so`. The `.so` name is a trick:
Android only extracts `lib*.so` from `jniLibs` into `nativeLibraryDir`, and that
is the one app-owned place where exec() is allowed (W^X since targetSdk 29).
The manifest needs `android:extractNativeLibs="true"` (or
`packaging.jniLibs.useLegacyPackaging = true`) for this to work.

Run it in the toolchain image, from the repo root:

    docker run --rm --memory 6g --user $(id -u):$(id -g) -e HOME=/tmp \
      -v ~/code/pocketshell:/work pocketshell-toolchain bash native/build-mosh.sh

About 3 minutes from scratch. Sources and intermediate installs go to
`native/build/` (gitignored). Each stage is stamped (`native/build/.done-*`), so
delete that file or the whole directory to rebuild a stage.

| Component | Version | Notes |
|-----------|---------|-------|
| NDK | r26d | target `aarch64-linux-android24` |
| protobuf | 3.21.12 | static `protobuf-lite`; last release without abseil; host protoc 21.12 in the image |
| OpenSSL | 3.0.15 | static libcrypto |
| ncurses | 6.4 | static `ncursesw`, `--disable-database --with-fallbacks=xterm-256color` |
| mosh | 1.4.0 | client only, `-static-libstdc++` |

Result: about 4.7 MB stripped. Its NEEDED entries are only Android system libs:
`libc.so libm.so libdl.so libz.so liblog.so`.

## Running it

    MOSH_KEY=<key from "MOSH CONNECT port key"> TERM=xterm-256color LANG=C.UTF-8 \
      libmoshclient.so <server IP address> <port>

- `TERM` must be `xterm-256color`. That's the only terminfo entry compiled in,
  and there is no terminfo database to fall back on. Other TERM values fail at
  startup.
- Set `LANG=C.UTF-8` (or `LC_ALL`). mosh-client refuses to start unless the
  locale is UTF-8.
- The first argument must be a numeric IP. mosh-client doesn't resolve
  hostnames, so resolve the host in the app first.
- The key is only read from the `MOSH_KEY` environment variable, never argv.

## Patch

`nl_langinfo()` only exists in bionic from API 26 on. The script replaces mosh's
one call to it, in `src/util/locale_utils.cc`, with
`MB_CUR_MAX > 1 ? "UTF-8" : "US-ASCII"`. That's the same thing bionic's own
`nl_langinfo(CODESET)` returns, so minSdk can stay at 24.

## mosh-server for the host (optional)

mosh 1.3.2, which Ubuntu 20.04 ships, predates truecolor. A program that emits 24-bit colors
(`38;2;r;g;b`), such as Claude Code inside herdr, which sets `COLORTERM=truecolor`, gets its colors
mangled by the server's terminal emulator before they ever reach the phone. 1.4.0 fixes that.
`build-mosh-server-host.sh` builds a fully static x86_64 1.4.0 server with musl in Alpine, so the
host needs no packages:

    docker run --rm --memory 4g -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) \
      -v ~/code/pocketshell:/work alpine:3.20 sh /work/native/build-mosh-server-host.sh

The result is `native/out/mosh-server-1.4.0-x86_64` (about 5.4 MB: protobuf 3.21.12, ncursesw 6.4
and the distro's static OpenSSL). The intermediate files go to `native/build-host/`. Both directories
are gitignored. mosh-server never reads terminfo; only the client does. So the binary runs as-is on
any x86_64 Linux.

To use it without replacing the system package:

    install -m755 native/out/mosh-server-1.4.0-x86_64 ~/.local/bin/mosh-server

Then set the host's **mosh-server command** in the app to
`~/.local/bin/mosh-server new -s -c 256 -l LANG=en_US.UTF-8`. Use the full path: a
non-interactive SSH command often doesn't have `~/.local/bin` on its PATH.
