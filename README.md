# Whorl

A plain Android SSH and mosh client for getting to your own machines. Saved machines, a real terminal and an
extra-keys row. No accounts, no sign-in, no paid tier, and no network traffic except to your own machines.

## Using it

- **Machines:** tap **Add machine**. Give it one or more addresses, a user, Mosh or SSH, and sign in with
  either the device key or a password (leave it blank to be asked each time). Tap a machine to connect;
  long-press it, or tap the pencil, to edit.
- **Several addresses:** list every way to reach a machine, such as its LAN address and its Tailscale one.
  Connecting tries them all, a moment apart, and uses the first that answers, preferring the ones listed
  first. When the network changes (Wi-Fi to cellular, say) and that address stops answering, the session
  reconnects over another one by itself. The terminal header shows the route in use and what's happening.
- **Device key:** the lock icon opens it. Generate an ed25519 key, or import one you already have, then copy or
  share the public key into `~/.ssh/authorized_keys` on each machine.
- **Host keys** are trusted on first use and kept in the app's private `known_hosts`, one entry per machine.
  If a key changes you'll be asked again.
- **Terminal:** tap to bring up the keyboard; swipe typing works. Pinch to resize text. The bar has a key to
  hide or show the keyboard, Esc, Tab, Ctrl and Alt (each latches for one key), a joystick key for the arrows
  (press and drag toward a direction, hold to repeat), Home/End/PgUp/PgDn, and symbols phone keyboards bury.
  **⋮** has paste, text size and disconnect. Long-press to select and copy.
- **Sessions:** the back arrow returns to the machine list without disconnecting, tapping the session name
  switches between open sessions, and ✕ ends one. Sessions keep running in the background behind a
  "Connected" notification.
- **Alerts:** when a program in a session you aren't looking at rings the bell, or asks for a notification
  (OSC 9, 777 or 99), you get an Android notification that opens that session. Over mosh only the bell gets
  through, so the notification shows the terminal's title.
- **herdr:** on a machine running [herdr](https://herdr.dev), Whorl watches its coding agents over a second SSH
  connection (to herdr's socket, so mosh sessions get it too), and herdr announces each change, so it isn't
  polling. An agent that needs you or finishes sends a
  notification naming it, and tapping it opens the session with herdr switched to that agent. The session card
  and the terminal header show "3 agents · 1 needs you", the header's session menu lists the agents to switch
  between, and a **herdr** key in the key row offers herdr's commands (workspaces, tabs, panes, detach)
  without chording Ctrl+B, plus **Open deck** for [deck](https://github.com/exterkamp/herdr-deck), a board of all
  the agents, when it's installed as a herdr plugin. It needs the device key or a saved password, since it connects without asking.
- **Run on connect:** a per-machine command typed into the shell once connected, e.g. `herdr` or
  `tmux new -A -s main`. Exiting it leaves you at the shell.
- **Reattach:** if the app is killed (an update, or Android reclaiming memory), opening it again reconnects
  every session that was open. mosh can't resume from a new client, so the old mosh-server is stopped and a
  new one started; with a multiplexer as the run-on-connect command you land back where you were.
- **Links:** long-press a URL for Open / Copy. It works on URLs that wrap across rows, including inside herdr
  and tmux panes. Long-press anywhere else selects text.
- **Look:** Material 3, in your wallpaper's colors on Android 12 and later; light and dark follow the system.
  The terminal stays dark either way, since herdr, Claude Code and most TUIs draw for a dark terminal; **⋮ →
  Light terminal in light mode** changes that.

### Mosh

The app signs in over SSH, runs `mosh-server new …` on the server, and hands the terminal to a bundled
`mosh-client`. The server needs mosh installed and UDP 60000–61000 reachable. The default **mosh-server
command** looks in `~/.local/bin`, `/opt/homebrew/bin` and `/usr/local/bin` before the system PATH, since a
non-interactive SSH login usually has none of them. If `mosh-server` is anywhere else, put its full path there.
It also sets `MOSH_SERVER_NETWORK_TMOUT=86400`, so a server that hasn't heard from the phone for a day exits rather
than lingering; a new mosh-client can't take over an old server anyway, and the app reconnects with a fresh one.

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
./build.sh recordPaparazziDebug  # render the main screens to app/src/test/snapshots/images
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
| `app/` | Kotlin app: Compose machine list and editor (`MainActivity`), View-based terminal (`TerminalActivity`), JSch SSH and the mosh handshake (`Ssh.kt`), sessions and the foreground service (`Sessions.kt`), racing a machine's addresses (`Reach.kt`), reconnecting on network changes (`Reconnector.kt`), notifications from sessions (`Alerts.kt`) |
| `terminal-emulator/`, `terminal-view/` | Termux's terminal emulator and view (Apache-2.0), changed so a session runs over a `TerminalTransport` (local pty or SSH channel); see their `NOTICE` |
| `native/` | mosh-client cross-compile, and a static mosh-server for hosts |
| `play/` | Play Store icon and feature graphic |
| `docker/` | build toolchain, emulator, and the test sshd |

## License

Whorl is licensed under the [Apache License 2.0](LICENSE). Copyright 2026 Shane Exterkamp.

Some parts keep their own licenses:

- `terminal-emulator/` and `terminal-view/` are Termux's, also Apache-2.0, with changes described in their `NOTICE`.
- `app/src/main/jniLibs/arm64-v8a/libmoshclient.so` is mosh-client, GPLv3 (with an OpenSSL linking exception). The
  app runs it as a separate program, and its source and build script are in `native/`.
- The fonts in `app/src/main/res/font/` are JetBrains Mono (SIL Open Font License 1.1) and a DejaVu Sans Mono
  subset (Bitstream Vera license).

`app/src/main/assets/licenses.txt`, shown in the app under About, has the full text of each.
