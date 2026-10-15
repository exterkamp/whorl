#!/usr/bin/env bash
# Render the Play listing graphics from the SVGs here. Runs in a throwaway container; installs nothing.
set -euo pipefail
cd "$(dirname "$0")"
docker run --rm -v "$PWD:/play" -v "$PWD/../app/src/main/res/font:/usr/share/fonts/jetbrains:ro" -w /play alpine:3.20 sh -c "
  apk add --no-cache rsvg-convert ttf-dejavu >/dev/null &&
  rsvg-convert -w 512 -h 512 icon.svg -o icon-512.png &&
  rsvg-convert -w 1024 -h 500 feature.svg -o feature-1024x500.png &&
  chown $(id -u):$(id -g) icon-512.png feature-1024x500.png"
echo "Rendered icon-512.png and feature-1024x500.png"
