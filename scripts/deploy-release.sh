#!/usr/bin/env bash
#
# Build (if needed) and install the PRODUCTION (release) APK onto a phone.
#
# Discovers the connected device automatically. If several are attached, pass a
# serial as the first argument or set ANDROID_SERIAL.
#
# Usage:
#   scripts/deploy-release.sh [serial]
#   SKIP_BUILD=1 scripts/deploy-release.sh    # install an already-built APK
#   REINSTALL=1  scripts/deploy-release.sh    # uninstall first (fixes signature clashes)
#
# Note: if a build signed with a *different* key is already installed (e.g. a
# debug build vs a real release keystore), Android rejects the upgrade. Rerun
# with REINSTALL=1 to uninstall the old copy first (this wipes the app's data).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

APK="$APP_APK_DIR/release/app-release.apk"
PACKAGE="com.example.timer"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  "$SCRIPT_DIR/build-release.sh"
fi
[[ -f "$APK" ]] || die "APK not found at $APK. Run scripts/build-release.sh first (or unset SKIP_BUILD)."

SERIAL="$(pick_device "${1:-}")"
ADB="$(resolve_adb)"

if [[ "${REINSTALL:-0}" == "1" ]]; then
  log "Uninstalling existing $PACKAGE from $SERIAL ..."
  "$ADB" -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
fi

log "Installing release APK on $SERIAL ..."
if ! "$ADB" -s "$SERIAL" install -r -d "$APK"; then
  die "Install failed. If this is a signature mismatch with an already-installed build, rerun: REINSTALL=1 scripts/deploy-release.sh"
fi

log "Done. Launching app..."
"$ADB" -s "$SERIAL" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  warn "Installed, but couldn't auto-launch. Open the app from the launcher."
log "Release build deployed to $SERIAL."
