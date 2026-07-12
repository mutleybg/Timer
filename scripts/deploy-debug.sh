#!/usr/bin/env bash
#
# Build (if needed) and install the DEBUG APK onto a connected phone.
#
# Discovers the connected device automatically. If several are attached, pass a
# serial as the first argument or set ANDROID_SERIAL.
#
# Usage:
#   scripts/deploy-debug.sh [serial]
#   SKIP_BUILD=1 scripts/deploy-debug.sh      # install an already-built APK
#   REINSTALL=1  scripts/deploy-debug.sh      # uninstall first (fixes signature clashes)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

APK="$APP_APK_DIR/debug/app-debug.apk"
PACKAGE="com.example.timer"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  "$SCRIPT_DIR/build-debug.sh"
fi
[[ -f "$APK" ]] || die "APK not found at $APK. Run scripts/build-debug.sh first (or unset SKIP_BUILD)."

SERIAL="$(pick_device "${1:-}")"
ADB="$(resolve_adb)"

if [[ "${REINSTALL:-0}" == "1" ]]; then
  log "Uninstalling existing $PACKAGE from $SERIAL ..."
  "$ADB" -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
fi

log "Installing debug APK on $SERIAL ..."
if ! "$ADB" -s "$SERIAL" install -r -d "$APK"; then
  die "Install failed. If this is a signature mismatch with an already-installed build, rerun: REINSTALL=1 scripts/deploy-debug.sh"
fi
log "Done. Launching app..."
"$ADB" -s "$SERIAL" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  warn "Installed, but couldn't auto-launch. Open the app from the launcher."
log "Debug build deployed to $SERIAL."
