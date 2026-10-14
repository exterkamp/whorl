# Publishing PocketShell on Google Play

Everything the Play Console asks for, in the order it asks.

## One-time setup (you)

1. **Developer account:** https://play.google.com/console, $25 once, personal or organization; identity
   verification follows. New personal accounts must run a **closed test with at least 12 testers, opted in for
   14 days in a row**, before they can apply for production access, so line up testers early.
2. **Upload key:** `./make-upload-key.sh` creates `~/.config/pocketshell/upload.jks` and its password file.
   **Back up `~/.config/pocketshell/`** (e.g. as a password-manager attachment). `./build.sh` picks it up
   automatically. In the Console, leave **Play App Signing** on (the default): Google holds the real app
   signing key, and this one only uploads.
3. **Make the GitHub repo public** before the first release. mosh-client is GPLv3, and the app's licenses
   screen points users to this repo for its source.

## Build

```bash
./build.sh bundleRelease    # app/build/outputs/bundle/release/app-release.aab
```

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload; Play rejects a repeat.

## App details

| Field | Value |
|---|---|
| App name | PocketShell |
| Package | `codes.exterkamp.pocketshell` (permanent once published) |
| Category | Tools |
| Free / paid | Free, no ads, no in-app purchases |
| Contact email | (your choice; shown publicly) |
| Privacy policy URL | https://github.com/exterkamp/pocketshell/blob/main/PRIVACY.md |

**Short description** (80 max):

> SSH and mosh terminal for your own machines. Saved hosts. No accounts, no ads.

**Full description:**

> PocketShell is a plain SSH and mosh client for getting to your own machines from your phone.
>
> • Save hosts once, connect with a tap: SSH or mosh, password or a device key (ed25519, generated or imported)
> • mosh sessions survive network switches and the phone sleeping
> • A real terminal: 256-color and truecolor, mouse support for tmux, herdr and other TUIs, pinch to zoom
> • An extra-keys row for Esc, Tab, Ctrl, Alt, arrows and the symbols phone keyboards hide
> • Several sessions at once; switch between them, and they keep running in the background
> • Run a command on connect (tmux, herdr…), and reconnect automatically if Android closes the app
> • Long-press links to open or copy them
>
> No accounts, no sign-in, no analytics, no ads. PocketShell only connects to the hosts you add, and your
> passwords and keys stay encrypted on your device. Open source: https://github.com/exterkamp/pocketshell

## Policy forms

**Data safety:**
- Does the app collect or share any of the required user data types? **No.**
- (The Console may still ask: data is encrypted in transit **Yes**; users can request deletion: **not
  applicable, nothing is collected**. Local storage on the device is not "collection".)

**Content rating questionnaire:** category *Utility, productivity, communication or other*; answer No to
everything. It should come out as Everyone / PEGI 3.

**Target audience:** 18 and over (it's a developer tool). Not designed for children.

**Ads:** No ads.

**Foreground service declaration** (`FOREGROUND_SERVICE_SPECIAL_USE`):
- Use case: *Keeps interactive SSH and mosh terminal sessions to the user's own servers connected while the
  app is in the background, so switching apps or locking the phone doesn't drop the session. The service runs
  only while at least one session is open, shows a persistent "Connected" notification naming the hosts, and
  stops when the user ends the last session.*
- The Console asks for a short video: record connecting to a host, switching to another app, the notification
  showing, and returning to the same session.

**App access:** all functionality is available without special access, but it needs a server to connect to.
Say so: "The app is an SSH client; reviewers can see the full UI by adding any SSH server they control. No
login to the app is required."

## Store listing graphics

In `play/` (regenerate with `play/make-graphics.sh`):
- `icon-512.png`: 512×512 app icon
- `feature-1024x500.png`: feature graphic
- `screenshots/*.png`: phone screenshots, 1080×2160 (Play requires an aspect ratio of 2:1 or less)

## Testing tracks

1. **Internal testing:** upload the first `.aab` here; up to 100 testers by email, available within minutes.
2. **Closed testing:** the 12-tester / 14-day requirement for new personal accounts. Share the opt-in link;
   testers must accept it and install from Play with the same Google account.
3. **Production:** apply from the Console once closed testing is complete.

Note: a phone that has the sideloaded debug-signed build must uninstall it before installing from Play, since
the signing keys differ. Hosts and keys are lost when you do; re-add them, or keep a list.
