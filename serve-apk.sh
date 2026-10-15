#!/usr/bin/env bash
# Serve just the release APK (build it with SIDELOAD=1 for a phone that has sideloaded builds) so a phone can download it in its browser:
#   devrun up --name apk --port-env PORT -- ./serve-apk.sh
# Only the APK is exposed: it's copied into its own directory first.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/serve
cp app/build/outputs/apk/release/app-release.apk build/serve/whorl.apk
exec python3 -m http.server "${PORT:?}" --bind 0.0.0.0 --directory build/serve
