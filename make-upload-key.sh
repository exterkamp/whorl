#!/usr/bin/env bash
# Create the Play upload key once, outside the repo, in ~/.config/pocketshell/.
# Back up that directory: losing the key means asking Google to reset it before you can ship updates.
set -euo pipefail
dir="$HOME/.config/pocketshell"
if [ -f "$dir/upload.jks" ]; then echo "$dir/upload.jks already exists; not overwriting." >&2; exit 1; fi
mkdir -p "$dir" && chmod 700 "$dir"
pass="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
docker run --rm --user "$(id -u):$(id -g)" -v "$dir:/out" pocketshell-toolchain \
  keytool -genkeypair -keystore /out/upload.jks -storetype PKCS12 -alias upload \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$pass" -keypass "$pass" -dname "CN=PocketShell upload key"
cat > "$dir/keystore.properties" <<PROPS
storeFile=/signing/upload.jks
storePassword=$pass
keyAlias=upload
keyPassword=$pass
PROPS
chmod 600 "$dir/upload.jks" "$dir/keystore.properties"
echo "Created $dir/upload.jks. Back up $dir somewhere safe (a password manager attachment works)."
