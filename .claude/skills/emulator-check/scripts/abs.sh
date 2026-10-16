#!/usr/bin/env bash
# Local Audiobookshelf server with a fixture library for emulator checks.
# Usage: abs.sh up | down | reset | status
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

LIBRARY_NAME="Emulator Books"

reachable() { curl -fsS -m 3 "$ABS_HOST_URL/status" >/dev/null 2>&1; }

# Colima's own port forwarder can silently fail to publish ports; tunnel through its SSH instead.
ensure_host_port() {
  # The first start initialises the database and can take a while.
  for _ in $(seq 1 30); do reachable && return 0; sleep 1; done
  if lsof -nP -i4TCP:"$ABS_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $ABS_PORT is published but ABS does not answer; see 'docker logs $ABS_CONTAINER'" >&2
    return 1
  fi
  if [[ -f "$HOME/.colima/ssh_config" ]]; then
    echo "Port $ABS_PORT not published by Docker; opening SSH tunnel into colima" >&2
    ssh -F "$HOME/.colima/ssh_config" -o ExitOnForwardFailure=yes -f -N \
      -L "127.0.0.1:$ABS_PORT:127.0.0.1:$ABS_PORT" colima
    for _ in $(seq 1 10); do reachable && return 0; sleep 1; done
  fi
  echo "ABS is not reachable at $ABS_HOST_URL" >&2
  return 1
}

close_tunnel() { pkill -f "L 127.0.0.1:$ABS_PORT:127.0.0.1:$ABS_PORT colima" 2>/dev/null || true; }

start_container() {
  mkdir -p "$STATE_DIR"/{config,metadata,audiobooks}
  if docker ps -a --format '{{.Names}}' | grep -qx "$ABS_CONTAINER"; then
    # Reuse only a container matching the current settings: older ones published on all interfaces, ran another
    # image, or were created with another ABS_PORT / ABSPLUS_EMU_STATE. Data stays in the bind mounts either way.
    local actual
    actual="$(docker inspect -f '{{range (index .HostConfig.PortBindings "80/tcp")}}{{.HostIp}}:{{.HostPort}} {{end}}{{.Config.Image}} {{range .Mounts}}{{if eq .Destination "/config"}}{{.Source}}{{end}}{{end}}' "$ABS_CONTAINER")"
    if [[ "$actual" == "127.0.0.1:$ABS_PORT $ABS_IMAGE $STATE_DIR/config" ]]; then
      docker start "$ABS_CONTAINER" >/dev/null
      return 0
    fi
    docker rm -f "$ABS_CONTAINER" >/dev/null
  fi
  # Loopback only: the server has a well-known admin password.
  docker run -d --name "$ABS_CONTAINER" -p "127.0.0.1:$ABS_PORT:80" \
      -v "$STATE_DIR/config:/config" -v "$STATE_DIR/metadata:/metadata" -v "$STATE_DIR/audiobooks:/audiobooks" \
      "$ABS_IMAGE" >/dev/null
}

# Silent audio keeps files tiny and the emulator quiet; position still advances during playback.
generate_fixtures() {
  # Each file is written under a temporary name and renamed, so an interrupted run is redone, not kept half-written.
  docker exec "$ABS_CONTAINER" sh -eu -c '
    ff() { out="$1"; shift; [ -f "$out" ] && return 0; mkdir -p "$(dirname "$out")"
      ffmpeg -nostdin -loglevel error -y "$@" "$out.tmp" && mv "$out.tmp" "$out"; }
    cover() { ff "$1" -f lavfi -i "testsrc2=s=600x600" -frames:v 1 -f image2; }
    silence() { echo -t "$1" -f lavfi -i anullsrc=r=22050:cl=mono; }

    d="/audiobooks/Ada Tester/Chaptered Book"
    printf ";FFMETADATA1\ntitle=Chaptered Book\nartist=Ada Tester\nalbum=Chaptered Book\n" > /tmp/meta.txt
    for i in 0 1 2; do
      printf "[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=Chapter %d\n" $((i*120000)) $(((i+1)*120000)) $((i+1)) >> /tmp/meta.txt
    done
    ff "$d/Chaptered Book.m4b" $(silence 360) -i /tmp/meta.txt -map_metadata 1 -map_chapters 1 -c:a aac -b:a 32k -f mp4
    cover "$d/cover.jpg"

    d="/audiobooks/Bob Example/Example Series/Vol 1 - Series Opener"
    for i in 1 2 3; do
      ff "$d/0$i.mp3" $(silence 60) -metadata track=$i -c:a libmp3lame -b:a 32k -f mp3
    done
    cover "$d/cover.jpg"

    ff "/audiobooks/Bob Example/Example Series/Vol 2 - Series Sequel/Series Sequel.mp3" $(silence 90) -c:a libmp3lame -b:a 32k -f mp3
  '
}

api() { # method path [json]
  local args=(-fsS -m 20 -X "$1" "$ABS_HOST_URL$2" -H "Authorization: Bearer $TOKEN")
  [[ $# -ge 3 ]] && args+=(-H 'Content-Type: application/json' -d "$3")
  curl "${args[@]}"
}

json() { node -e "const j=JSON.parse(require('fs').readFileSync(0,'utf8'));const v=($1);if(v!==undefined)console.log(v)"; }

seed() {
  if [[ "$(curl -fsS "$ABS_HOST_URL/status" | json 'j.isInit')" != "true" ]]; then
    curl -fsS -X POST "$ABS_HOST_URL/init" -H 'Content-Type: application/json' \
      -d "{\"newRoot\":{\"username\":\"$ABS_USER\",\"password\":\"$ABS_PASSWORD\"}}" >/dev/null
  fi
  TOKEN="$(curl -fsS -X POST "$ABS_HOST_URL/login" -H 'Content-Type: application/json' -H 'x-return-tokens: true' \
    -d "{\"username\":\"$ABS_USER\",\"password\":\"$ABS_PASSWORD\"}" | json 'j.user.accessToken || j.user.token')"

  generate_fixtures

  local lib_id
  lib_id="$(api GET /api/libraries | json "(j.libraries.find(l => l.name === '$LIBRARY_NAME') || {}).id")"
  if [[ -z "$lib_id" ]]; then
    lib_id="$(api POST /api/libraries "{\"name\":\"$LIBRARY_NAME\",\"mediaType\":\"book\",\"folders\":[{\"fullPath\":\"/audiobooks\"}]}" | json 'j.id')"
  fi
  api POST "/api/libraries/$lib_id/scan" >/dev/null

  local count=0
  for _ in $(seq 1 30); do
    count="$(api GET "/api/libraries/$lib_id/items?limit=0" 2>/dev/null | json 'j.total' 2>/dev/null || echo 0)"
    [[ "$count" -ge 3 ]] && break
    sleep 1
  done
  [[ "$count" -ge 3 ]] || { echo "Library scan found $count of 3 fixture books" >&2; return 1; }
  echo "ABS ready: $ABS_HOST_URL (emulator: $ABS_EMULATOR_URL), user $ABS_USER/$ABS_PASSWORD, library '$LIBRARY_NAME' with $count books"
}

case "${1:-}" in
  up) start_container; ensure_host_port; seed ;;
  down) close_tunnel; docker stop "$ABS_CONTAINER" >/dev/null 2>&1 || true; echo "ABS stopped (data kept in $STATE_DIR)" ;;
  reset)
    close_tunnel
    docker rm -f "$ABS_CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$STATE_DIR"
    start_container; ensure_host_port; seed ;;
  status) reachable && curl -fsS "$ABS_HOST_URL/status" && echo || { echo "ABS not reachable at $ABS_HOST_URL"; exit 1; } ;;
  *) echo "Usage: $0 up|down|reset|status" >&2; exit 2 ;;
esac
