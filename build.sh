#!/usr/bin/env bash
# Build the APK inside the toolchain container; nothing Android-related is installed on the host.
#   ./build.sh                 debug APK  -> app/build/outputs/apk/debug/app-debug.apk
#   ./build.sh assembleRelease release APK (minified; upload-key signed if one exists, else debug-signed)
#   SIDELOAD=1 ./build.sh assembleRelease   release APK signed with the debug key, for a phone that has
#                                           earlier sideloaded builds
#   ./build.sh bundleRelease   release App Bundle for Play -> app/build/outputs/bundle/release/app-release.aab
#   ./build.sh <gradle args>   anything else
set -euo pipefail
cd "$(dirname "$0")"

# Shown on the About screen, so two phones can be told apart when builds share a version number.
GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
git diff --quiet HEAD 2>/dev/null || GIT_COMMIT="$GIT_COMMIT+"

docker image inspect pocketshell-toolchain >/dev/null 2>&1 || docker build -t pocketshell-toolchain docker/

# Keep the Gradle cache and the debug signing key on the host so builds are incremental and
# successive APKs install over each other.
mkdir -p "$HOME/.cache/pocketshell/gradle" "$HOME/.cache/pocketshell/android"

# The Play upload key, if this machine has one (created by ./make-upload-key.sh).
SIGNING_DIR="$HOME/.config/pocketshell"
[ -f "$SIGNING_DIR/keystore.properties" ] || SIGNING_DIR=""
# SIDELOAD=1 signs release builds with the debug key instead, so they install over earlier sideloaded builds.
[ -z "${SIDELOAD:-}" ] || SIGNING_DIR=""

docker run --rm --memory 6g \
  --user "$(id -u):$(id -g)" \
  -e HOME=/home/builder -e GRADLE_USER_HOME=/home/builder/.gradle \
  -v "$HOME/.cache/pocketshell/gradle:/home/builder/.gradle" \
  -v "$HOME/.cache/pocketshell/android:/home/builder/.android" \
  ${SIGNING_DIR:+-v "$SIGNING_DIR:/signing:ro" -e POCKETSHELL_SIGNING=/signing/keystore.properties} \
  -v "$PWD:/work" -w /work \
  ${TEST_SSH_HOST:+-e TEST_SSH_HOST="$TEST_SSH_HOST"} \
  -e GIT_COMMIT="$GIT_COMMIT" \
  pocketshell-toolchain \
  gradle --no-daemon --console=plain "${@:-assembleDebug}"
