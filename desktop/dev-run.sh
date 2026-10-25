#!/usr/bin/env bash
# Run the desktop app on a virtual display against docker/test-server, type into it, and save screenshots to
# build/desktop-shots/. Nothing is installed on the host; it all runs in containers.
#   desktop/dev-run.sh [ssh|mosh]
set -euo pipefail
cd "$(dirname "$0")/.."
PROTOCOL=$(echo "${1:-ssh}" | tr '[:lower:]' '[:upper:]')

docker image inspect pocketshell-test-server >/dev/null 2>&1 || docker build -t pocketshell-test-server docker/test-server
docker image inspect pocketshell-toolchain >/dev/null 2>&1 || docker build -t pocketshell-toolchain docker/
docker image inspect pocketshell-desktop >/dev/null 2>&1 || docker build -t pocketshell-desktop -f docker/Dockerfile.desktop docker/

SERVER=pocketshell-sshd-desktop
docker rm -f "$SERVER" >/dev/null 2>&1 || true
docker run -d --rm --name "$SERVER" pocketshell-test-server >/dev/null
trap 'docker rm -f "$SERVER" >/dev/null 2>&1 || true' EXIT
IP=$(docker inspect -f '{{.NetworkSettings.IPAddress}}' "$SERVER")

# A fresh data directory with one machine, its host keys already trusted (so there's no prompt to click).
HOME_DIR=build/desktop-home
OUT=build/desktop-shots
rm -rf "$HOME_DIR"; mkdir -p "$HOME_DIR" "$OUT"
ID=dev-test-server
cat > "$HOME_DIR/whorl.json" <<JSON
{"profiles":[{"id":"$ID","name":"test-server","addresses":[{"host":"$IP","port":0}],"port":22,"user":"test",
"protocol":"$PROTOCOL","auth":"PASSWORD","password":"test","startup":""}],"fontSize":14}
JSON
sleep 1
docker exec "$SERVER" sh -c 'cat /etc/ssh/ssh_host_*_key.pub' | awk -v alias="whorl-machine-$ID" '{print alias, $1, $2}' > "$HOME_DIR/known_hosts"

mkdir -p "$HOME/.cache/pocketshell/gradle" "$HOME/.cache/pocketshell/android"
docker run --rm --memory 6g --user "$(id -u):$(id -g)" \
  -e HOME=/home/builder -e GRADLE_USER_HOME=/home/builder/.gradle \
  -v "$HOME/.cache/pocketshell/gradle:/home/builder/.gradle" \
  -v "$HOME/.cache/pocketshell/android:/home/builder/.android" \
  -v "$PWD:/work" -w /work \
  -e WHORL_HOME="/work/$HOME_DIR" -e WHORL_CONNECT=test-server -e SKIKO_RENDER_API=SOFTWARE \
  -e GIT_COMMIT="$(git rev-parse --short HEAD)" \
  pocketshell-desktop bash -c '
    set -e
    Xvfb :99 -screen 0 1280x800x24 >/dev/null 2>&1 &
    export DISPLAY=:99
    gradle --no-daemon -q --console=plain :desktop:run >/work/build/desktop-shots/run.log 2>&1 &
    for i in $(seq 1 180); do xdotool search --name "^Whorl$" >/dev/null 2>&1 && break; sleep 1; done
    sleep 10
    import -window root /work/build/desktop-shots/1-connected.png
    xdotool type --delay 40 "echo hello from whorl desktop; uname -sr; printf \"\\e[1;31mred \\e[32mgreen \\e[44mblue-bg\\e[0m\\n\""
    xdotool key Return
    sleep 3
    import -window root /work/build/desktop-shots/2-typed.png
    xdotool type --delay 40 "top -b -n1 | head -15"; xdotool key Return
    sleep 3
    import -window root /work/build/desktop-shots/3-top.png
    # Copy and paste. Rows are 25px from the terminal top (73px into the window), columns 11px from 8px in.
    W=$(xdotool search --name "^Whorl$" | head -1)
    eval "$(xdotool getwindowgeometry --shell "$W")"
    row() { echo $((Y + 73 + $1 * 25 + 12)); }
    col() { echo $((X + 8 + $1 * 11 + 5)); }
    CLIP=/work/build/desktop-shots/clipboard.txt; : > $CLIP
    # 1. Drag across "hello from whorl desktop" (row 3), Ctrl+C.
    xdotool mousemove $(col 0) $(row 3) mousedown 1 mousemove $(col 10) $(row 3) mousemove $(col 23) $(row 3) mouseup 1
    sleep 1; import -window root /work/build/desktop-shots/5-selected.png
    xdotool key ctrl+c; sleep 1
    echo "1 drag + Ctrl+C: [$(xclip -o -selection clipboard 2>&1)]" >> $CLIP
    # 2. Right-click menu.
    xdotool mousemove $(col 30) $(row 8) click 3; sleep 1
    import -window root /work/build/desktop-shots/6-menu.png
    xdotool key Escape; sleep 1
    # 3. With mouse reporting on (as herdr has it), a drag still selects; double-click selects a word.
    xdotool type --delay 30 "printf \"\\e[?1000h\"; clear; echo mouse reporting on; echo alpha beta gamma"; xdotool key Return; sleep 1
    import -window root /work/build/desktop-shots/7a-reporting.png
    xdotool mousemove $(col 6) $(row 0) mousedown 1 mousemove $(col 9) $(row 0) mousemove $(col 15) $(row 0) mouseup 1; sleep 1
    xdotool key ctrl+shift+c; sleep 1
    echo "3 drag with mouse reporting on: [$(xclip -o -selection clipboard 2>&1)]" >> $CLIP
    xdotool mousemove $(col 7) $(row 1) click --repeat 2 --delay 120 1; sleep 1
    import -window root /work/build/desktop-shots/7-word.png
    xdotool key ctrl+c; sleep 1
    echo "3b double-click word: [$(xclip -o -selection clipboard 2>&1)]" >> $CLIP
    xdotool type --delay 30 "printf \"\\e[?1000l\""; xdotool key Return; sleep 1
    # 4. Ctrl+V pastes.
    printf "echo pasted-from-clipboard" | xclip -selection clipboard
    xdotool key ctrl+v; sleep 1; xdotool key Return; sleep 1
    import -window root /work/build/desktop-shots/8-pasted.png
    # 5. Ctrl+C with nothing selected still interrupts.
    xdotool type --delay 30 "sleep 30"; xdotool key Return; sleep 1; xdotool key ctrl+c; sleep 1
    import -window root /work/build/desktop-shots/9-interrupted.png
    xdotool key ctrl+shift+t
    sleep 2
    import -window root /work/build/desktop-shots/4-machines.png
    pkill -f GradleWorkerMain || true; pkill -f "codes.exterkamp.whorl.desktop.MainKt" || true; pkill -f gradle || true
  ' || true
ls -la "$OUT"
