# Shared settings for the emulator-check scripts. Sourced, not executed.

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(git -C "$SKILL_DIR" rev-parse --show-toplevel)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools; do
    if [[ -n "$candidate" && -d "$candidate/platform-tools" ]]; then ANDROID_HOME="$candidate"; break; fi
  done
fi
[[ -n "${ANDROID_HOME:-}" ]] || { echo "Android SDK not found; set ANDROID_HOME" >&2; exit 1; }
export ANDROID_HOME

ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$(command -v avdmanager || echo "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager")"
SDKMANAGER="$(command -v sdkmanager || echo "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager")"

AVD_NAME="${AVD_NAME:-absplus-api35}"

# Serial of the running emulator of this AVD, empty if none. Other devices (a phone, another task's AVD) are never picked.
stand_serial() {
  local s
  for s in $("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1}'); do
    [[ "$("$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" == "$AVD_NAME" ]] && { echo "$s"; return 0; }
  done
  return 0
}

# Pins every later adb call, including the ones in cdp.mjs, to the stand emulator.
use_stand() {
  ANDROID_SERIAL="$(stand_serial)"
  [[ -n "$ANDROID_SERIAL" ]] || { echo "Emulator $AVD_NAME is not running; start it with emu.sh up" >&2; exit 1; }
  export ANDROID_SERIAL
}
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-35;google_apis;arm64-v8a}"

APP_ID="app.absplus.android.debug"
APK="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

STATE_DIR="${ABSPLUS_EMU_STATE:-$HOME/.local/state/absplus-emulator}"
ABS_CONTAINER="absplus-emulator-abs"
ABS_IMAGE="ghcr.io/advplyr/audiobookshelf:2.37.1"
ABS_PORT="${ABS_PORT:-13378}"
ABS_HOST_URL="http://127.0.0.1:$ABS_PORT"
# Reached through `adb reverse`: the emulator's 10.0.2.2 host alias refused TCP on this setup.
ABS_EMULATOR_URL="http://127.0.0.1:$ABS_PORT"
ABS_USER="root"
ABS_PASSWORD="root"

# Gradle 8.11 runs on JDK 17–23; the newest installed JDK may be outside that range.
pick_java_home() {
  local v home
  for v in 21 17 22 23; do
    home="$(/usr/libexec/java_home -v "$v" 2>/dev/null)" || continue
    if "$home/bin/java" -version 2>&1 | grep -q "version \"$v"; then echo "$home"; return 0; fi
  done
  return 1
}
