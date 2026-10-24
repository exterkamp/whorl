# Whorl for Windows

The desktop version of Whorl, built with Compose Desktop on the same shared code as the Android app
(`:core` for machines, SSH, the mosh handshake and herdr; `:terminal-core` for the terminal emulator).

## Install

Download `Whorl-<version>.msi` from the
[latest desktop release](https://github.com/exterkamp/whorl/releases) (or, for any commit, the `Whorl-msi`
artifact of its [Windows desktop](../.github/workflows/windows.yml) run) and run it. It installs per user, with
a Start-menu entry, and a newer MSI upgrades it in place.

## What it does

- Machines with several addresses (raced, preferred first), SSH or mosh, device key or password, a command to run
  on connect. Same model as the phone.
- Tabs, one per machine; a dropped connection reconnects by itself after 3 seconds.
- herdr: the header shows the machine's agents and switches to one; an agent that needs you or finishes pops a
  Windows notification (click it to jump there) unless you're already looking at it.
- Keys: Ctrl+Shift+C / Ctrl+Shift+V copy and paste (Ctrl+C copies when there's a selection, right-click copies or
  pastes), Ctrl+Shift+T machine list, Ctrl+Tab next tab, Ctrl+Shift+W close tab, Ctrl+= / Ctrl+- text size.
  Shift+drag selects even when a program has the mouse.

Settings and keys are in `%APPDATA%\Whorl\whorl.dat`, encrypted with DPAPI for your Windows user;
trusted host keys are in `%APPDATA%\Whorl\known_hosts`.

## mosh on Windows

mosh has no native Windows build, so the MSI carries Cygwin's `mosh-client.exe` and the Cygwin DLLs it needs
(`mosh/bin/`, with `mosh/usr/share/terminfo/` for the terminal description), and Whorl runs it in a ConPTY
pseudo-console. CI installs Cygwin's `mosh` package to collect them, then runs a test that starts a mosh-server
and types through the bundled client to prove the bundle works. mosh is GPLv3; its license ships alongside.

## Developing on Linux

Nothing is installed on the host; everything runs in containers.

```bash
desktop/dev-run.sh ssh     # run the app on a virtual display against docker/test-server; screenshots in build/desktop-shots/
desktop/dev-run.sh mosh    # same over mosh (Linux uses the system mosh-client)
./build.sh :desktop:test   # unit tests (the mosh end-to-end test needs WHORL_MOSH_E2E=1 and a mosh install)
```

`WHORL_HOME` points the app at another data directory and `WHORL_CONNECT=<machine name>` connects on launch.
The MSI itself can only be built on Windows: `gradle :desktop:packageMsi` with `WHORL_DESKTOP_ONLY=1`.
