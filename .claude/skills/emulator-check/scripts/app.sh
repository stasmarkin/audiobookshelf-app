#!/usr/bin/env bash
# Debug build of this worktree on the running emulator.
# Usage: app.sh deploy [--native-only] | build [--native-only] | install | launch | clear | login | screenshot <file.png> | cdp <args...> | adb <args...>
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
SCRIPTS="$SKILL_DIR/scripts"

build() {
  if [[ "${1:-}" != "--native-only" ]]; then
    (cd "$REPO_ROOT" && npm run generate && npx cap sync android)
  fi
  local java_home
  java_home="$(pick_java_home)" || { echo "Need JDK 21–23 (Capacitor targets Java 21, Gradle 8.11 runs on up to 23)" >&2; exit 1; }
  (cd "$REPO_ROOT/android" && JAVA_HOME="$java_home" ./gradlew assembleDebug --console=plain -q)
  echo "Built $APK"
}

# The runtime prompt would cover the UI on first launch; pm clear revokes it again.
grant_permissions() { "$ADB" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS; }

install() { "$ADB" install -r "$APK" >/dev/null; grant_permissions; echo "Installed $APP_ID"; }

launch() {
  "$ADB" reverse "tcp:$ABS_PORT" "tcp:$ABS_PORT" >/dev/null
  "$ADB" shell am force-stop "$APP_ID"
  "$ADB" shell am start -W -n "$APP_ID/app.absplus.android.MainActivity" >/dev/null
  # The WebView DevTools socket appears once the bundle has loaded.
  for _ in $(seq 1 30); do
    cdp eval 'document.readyState' 2>/dev/null | grep -q complete && { echo "Launched $APP_ID"; return 0; }
    sleep 1
  done
  echo "App launched but the WebView is not reachable over CDP" >&2
  return 1
}

cdp() { ADB="$ADB" APP_ID="$APP_ID" node "$SCRIPTS/cdp.mjs" "$@"; }

login() {
  cdp eval "\$nuxt.\$router.push('/connect'), undefined"
  cdp wait-text 'Server address' 20 >/dev/null 2>&1 || {
    echo "Server address form not shown (logged in, or a saved server is listed); run 'app.sh clear' and 'app.sh launch' first" >&2
    return 1
  }
  cdp fill 'input[type=url]' "$ABS_EMULATOR_URL"
  cdp click-text 'Submit'
  cdp wait-for 'input[type=password]' 20
  cdp fill "input:not([type=password])" "$ABS_USER"
  cdp fill 'input[type=password]' "$ABS_PASSWORD"
  cdp click-text 'Submit'
  cdp eval "new Promise((resolve, reject) => {
    const deadline = Date.now() + 20000
    const tick = () => \$nuxt.\$store.state.user.user ? resolve('Logged in as ' + \$nuxt.\$store.state.user.user.username)
      : Date.now() > deadline ? reject(new Error('Login did not complete: ' + document.body.innerText.slice(0, 300)))
      : setTimeout(tick, 500)
    tick()
  })"
}

[[ "${1:-}" == build ]] || use_stand

case "${1:-}" in
  deploy) shift; build "$@"; install; launch ;;
  build) shift; build "$@" ;;
  install) install ;;
  launch) launch ;;
  clear) "$ADB" shell pm clear "$APP_ID" >/dev/null; grant_permissions; echo "Cleared app data" ;;
  login) login ;;
  screenshot) mkdir -p "$(dirname "${2:?screenshot path}")"; "$ADB" exec-out screencap -p >"$2" && echo "$2" ;;
  cdp) shift; cdp "$@" ;;
  adb) shift; "$ADB" "$@" ;;
  *) echo "Usage: $0 deploy|build|install|launch|clear|login|screenshot <file>|cdp <args>|adb <args>" >&2; exit 2 ;;
esac
