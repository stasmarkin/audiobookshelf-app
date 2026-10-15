#!/usr/bin/env bash
# Android emulator for checks. Usage: emu.sh up [--window] | down | status
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

ensure_avd() {
  "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD_NAME" && return 0
  if [[ ! -x "$EMULATOR" || ! -d "$ANDROID_HOME/${SYSTEM_IMAGE//;//}" ]]; then
    # `yes` dies of SIGPIPE once sdkmanager exits; only sdkmanager's status matters.
    { yes || true; } | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "emulator" "$SYSTEM_IMAGE" >/dev/null
  fi
  # avdmanager complains about a missing devices.xml in the image; harmless.
  echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_6 2>&1 | { grep -v devices.xml || true; } >&2
}

up() {
  local serial
  serial="$(stand_serial)"
  if [[ -n "$serial" ]]; then echo "Emulator already running: $serial ($AVD_NAME)"; return 0; fi

  ensure_avd
  mkdir -p "$STATE_DIR"
  local args=(-avd "$AVD_NAME" -no-boot-anim -no-snapshot-save -netdelay none -netspeed full)
  [[ "${1:-}" == "--window" ]] || args+=(-no-window)
  nohup "$EMULATOR" "${args[@]}" >"$STATE_DIR/emulator.log" 2>&1 &
  local pid=$!

  for _ in $(seq 1 180); do
    kill -0 "$pid" 2>/dev/null || { echo "Emulator exited; see $STATE_DIR/emulator.log" >&2; exit 1; }
    serial="$(stand_serial)"
    [[ -n "$serial" && "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    serial=""
    sleep 1
  done
  [[ -n "$serial" ]] || { echo "Emulator did not boot in 3 minutes; see $STATE_DIR/emulator.log" >&2; exit 1; }

  # Animations make UI dumps and screenshots racy.
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$serial" shell settings put global "$s" 0
  done
  echo "Emulator ready: $serial ($AVD_NAME)"
}

case "${1:-}" in
  up) shift; up "$@" ;;
  down) serial="$(stand_serial)"; [[ -n "$serial" ]] && "$ADB" -s "$serial" emu kill >/dev/null; echo "Emulator stopped" ;;
  status) serial="$(stand_serial)"; [[ -n "$serial" ]] && echo "Running: $serial ($AVD_NAME)" || { echo "$AVD_NAME is not running"; exit 1; } ;;
  *) echo "Usage: $0 up [--window]|down|status" >&2; exit 2 ;;
esac
