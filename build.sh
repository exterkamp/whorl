#!/usr/bin/env bash
# Build the APK inside the toolchain container; nothing Android-related is installed on the host.
#   ./build.sh                 debug APK  -> app/build/outputs/apk/debug/app-debug.apk
#   ./build.sh assembleRelease release APK (minified, debug-signed for sideloading)
#   ./build.sh <gradle args>   anything else
set -euo pipefail
cd "$(dirname "$0")"

docker image inspect pocketshell-toolchain >/dev/null 2>&1 || docker build -t pocketshell-toolchain docker/

# Keep the Gradle cache and the debug signing key on the host so builds are incremental and
# successive APKs install over each other.
mkdir -p "$HOME/.cache/pocketshell/gradle" "$HOME/.cache/pocketshell/android"

docker run --rm --memory 6g \
  --user "$(id -u):$(id -g)" \
  -e HOME=/home/builder -e GRADLE_USER_HOME=/home/builder/.gradle \
  -v "$HOME/.cache/pocketshell/gradle:/home/builder/.gradle" \
  -v "$HOME/.cache/pocketshell/android:/home/builder/.android" \
  -v "$PWD:/work" -w /work \
  ${TEST_SSH_HOST:+-e TEST_SSH_HOST="$TEST_SSH_HOST"} \
  pocketshell-toolchain \
  gradle --no-daemon --console=plain "${@:-assembleDebug}"
