# Conch

A bare-bones Android SSH and mosh client. Saved hosts, a terminal and an extra-keys row. No accounts, no
sign-in, no paid tier, and no network traffic except to your own hosts.

## Using it

- **Hosts:** tap **+** to add one. Choose Mosh or SSH, and sign in with either the device key or a password.
  Leave the password blank to be asked each time. Tap a host to connect. Long-press it, or tap the pencil, to edit.
- **Device key:** the lock icon on the host list opens it. Generate an ed25519 key, or import one you already
  have, then copy or share the public key into `~/.ssh/authorized_keys` on each machine.
- **Host keys** are trusted on first use and kept in the app's private `known_hosts`. If a key changes you'll
  be asked again.
- **Terminal:** tap to bring up the keyboard and pinch to resize text. The bar has esc, tab, ctrl and alt
  (each latches for one key), arrows, home/end/pgup/pgdn, and a few symbols that phone keyboards bury.
  **⋮** has paste, text size and disconnect. Long-press to select and copy.
- **Sessions:** the header's ⌂ goes back to the host list without disconnecting, tapping the session name
  switches between open sessions, and ✕ ends one. The host list shows open sessions at the top, where you
  tap to resume or ✕ to end. Sessions keep running in the background behind a "Connected" notification.
- **Run on connect:** a per-host command typed into the shell once connected, e.g. `herdr` or
  `tmux new -A -s main`. Exiting it leaves you at the shell.
- **Reattach:** if the app is killed (an update, or Android reclaiming memory), opening it again reconnects
  every session that was open. mosh can't resume from a new client, so the old mosh-server is stopped and a
  new one started; with a multiplexer as the run-on-connect command you land back where you were.
- **Links:** long-press a URL for Open / Copy. It works on URLs that wrap across rows, including inside herdr
  and tmux panes. Long-press anywhere else selects text.

### Mosh

The app signs in over SSH, runs `mosh-server new …` on the server, and hands the terminal to a bundled
`mosh-client`. The server needs mosh installed and UDP 60000–61000 reachable. The default **mosh-server
command** looks in `~/.local/bin`, `/opt/homebrew/bin` and `/usr/local/bin` before the system PATH, since a
non-interactive SSH login usually has none of them. If `mosh-server` is anywhere else, put its full path there.

mosh-server older than 1.4.0 (Ubuntu 20.04 ships 1.3.2) mangles 24-bit color, which shows up as dark text in
Claude Code under herdr or tmux. `native/build-mosh-server-host.sh` builds a static 1.4.0 server. Install it as
`~/.local/bin/mosh-server`, no root needed, and the default command picks it up ahead of the system one; see
`native/README.md`.

## Publishing

See [PLAY.md](PLAY.md) for the Google Play checklist and [PRIVACY.md](PRIVACY.md) for the privacy policy.

## Building

Everything builds in Docker. Nothing Android-related is installed on the host.

```bash
./build.sh                    # debug APK  -> app/build/outputs/apk/debug/app-debug.apk
./build.sh assembleRelease    # minified, debug-signed APK for sideloading
./build.sh testDebugUnitTest  # JVM tests
```

The Gradle cache and the debug signing key live in `~/.cache/pocketshell/`, so later builds are incremental
and each new APK installs over the previous one.

Install by copying the APK to the phone and opening it, or with `adb install -r app-debug.apk`. The app is
arm64 only, which covers essentially every phone from the last eight years.

### Tests

`SshTest` covers mosh handshake parsing and key generation and import. Its integration test runs only when
`TEST_SSH_HOST` is set. It logs in to a throwaway sshd, installs a newly generated key, logs back in with only
that key, and starts `mosh-server` with the app's default command:

```bash
docker build -t pocketshell-test-server docker/test-server
docker run -d --rm --name pocketshell-sshd pocketshell-test-server
TEST_SSH_HOST=$(docker inspect -f '{{.NetworkSettings.IPAddress}}' pocketshell-sshd) ./build.sh testDebugUnitTest
docker stop pocketshell-sshd
```

### mosh-client

`app/src/main/jniLibs/arm64-v8a/libmoshclient.so` is a static mosh 1.4.0 client built by
`native/build-mosh.sh`. See `native/README.md`. It's checked in, so the app builds without rebuilding it.

## Layout

| Path | What |
|------|------|
| `app/` | Kotlin app: Compose host list and editor (`MainActivity`), View-based terminal (`TerminalActivity`), JSch SSH and the mosh handshake (`Ssh.kt`), sessions and the foreground service (`Sessions.kt`) |
| `terminal-emulator/`, `terminal-view/` | Termux's terminal emulator and view (Apache-2.0), changed so a session runs over a `TerminalTransport` (local pty or SSH channel); see their `NOTICE` |
| `native/` | mosh-client cross-compile |
| `docker/` | build toolchain, emulator, and the test sshd |
