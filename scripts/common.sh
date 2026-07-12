#!/usr/bin/env bash
#
# Shared helpers for the build/deploy scripts. Source this; don't run it.
# Resolves the project root, a compatible JDK, and the Android SDK / adb,
# and provides device-discovery helpers used by the deploy scripts.

set -euo pipefail

# --- Paths -------------------------------------------------------------------

# Directory of this file, then the repo root (its parent).
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$COMMON_DIR/.." && pwd)"

GRADLEW="$PROJECT_ROOT/gradlew"
APP_APK_DIR="$PROJECT_ROOT/app/build/outputs/apk"

# --- JDK ---------------------------------------------------------------------
# Gradle here needs a JDK 17-21; the machine default may be newer. We pick a
# compatible one *for this process only* and never touch the global JAVA_HOME.
resolve_java_home() {
  # Honour an already-good JAVA_HOME if the caller set one on purpose.
  local candidates=(
    "${JAVA_HOME:-}"
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  )
  for c in "${candidates[@]}"; do
    if [[ -n "$c" && -x "$c/bin/javac" ]]; then
      echo "$c"
      return 0
    fi
  done
  echo ""  # nothing found
}

# --- Android SDK / tools -----------------------------------------------------

resolve_sdk_dir() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then echo "$ANDROID_HOME"; return; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then echo "$ANDROID_SDK_ROOT"; return; fi
  # Fall back to sdk.dir in local.properties, then the macOS default location.
  if [[ -f "$PROJECT_ROOT/local.properties" ]]; then
    local d
    d="$(grep -E '^sdk\.dir=' "$PROJECT_ROOT/local.properties" | head -1 | cut -d= -f2-)"
    if [[ -n "$d" ]]; then echo "$d"; return; fi
  fi
  echo "$HOME/Library/Android/sdk"
}

resolve_adb() {
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  local sdk; sdk="$(resolve_sdk_dir)"
  if [[ -x "$sdk/platform-tools/adb" ]]; then echo "$sdk/platform-tools/adb"; return; fi
  echo ""
}

# Newest build-tools dir (for apksigner), or empty.
resolve_build_tools() {
  local sdk; sdk="$(resolve_sdk_dir)"
  local bt="$sdk/build-tools"
  [[ -d "$bt" ]] || { echo ""; return; }
  ls -1 "$bt" | sort -V | tail -1 | sed "s#^#$bt/#"
}

# --- Logging -----------------------------------------------------------------
log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

# --- Device discovery --------------------------------------------------------
# Prints the list of serials that are in the "device" (ready) state.
list_ready_devices() {
  local adb; adb="$(resolve_adb)"
  [[ -n "$adb" ]] || return 0
  "$adb" start-server >/dev/null 2>&1 || true
  "$adb" devices | awk 'NR>1 && $2=="device" {print $1}'
}

# Warn (non-fatally) about devices seen but not usable.
report_unusable_devices() {
  local adb; adb="$(resolve_adb)"
  [[ -n "$adb" ]] || return 0
  "$adb" devices | awk 'NR>1 && $2!="device" && $2!="" {print $1"\t"$2}' | while IFS=$'\t' read -r serial state; do
    warn "Ignoring device $serial (state: $state) — 'unauthorized' means accept the USB-debugging prompt on the phone; 'offline' usually means unplug/replug."
  done
}

# Echoes exactly one target serial to stdout, or exits with a helpful message.
# Honours ANDROID_SERIAL / a serial passed as $1 when several are attached.
pick_device() {
  local wanted="${1:-${ANDROID_SERIAL:-}}"
  local adb; adb="$(resolve_adb)"
  [[ -n "$adb" ]] || die "adb not found. Install Android platform-tools or set ANDROID_HOME."

  report_unusable_devices

  local devices
  devices="$(list_ready_devices)"
  local count
  count="$(printf '%s\n' "$devices" | grep -c . || true)"

  if [[ "$count" -eq 0 ]]; then
    die "No phone detected. Connect a device over USB, enable USB debugging, and accept the prompt (or start an emulator). Check with: $adb devices"
  fi

  if [[ -n "$wanted" ]]; then
    if printf '%s\n' "$devices" | grep -qx "$wanted"; then
      echo "$wanted"; return 0
    fi
    die "Requested device '$wanted' is not connected/ready. Connected: $(printf '%s ' $devices)"
  fi

  if [[ "$count" -gt 1 ]]; then
    # No explicit choice: prefer a real device over an emulator by picking the
    # first serial that does not contain "emulator" (adb names emulators
    # "emulator-<port>"). Set ANDROID_SERIAL / pass a serial to override.
    local physical
    physical="$(printf '%s\n' "$devices" | grep -v 'emulator' | head -1)"
    if [[ -n "$physical" ]]; then
      warn "Multiple devices connected; deploying to non-emulator device $physical. Set ANDROID_SERIAL to target another."
      echo "$physical"; return 0
    fi
    die "Multiple devices connected but all are emulators: $(printf '%s ' $devices). Choose one: pass its serial as an argument or set ANDROID_SERIAL."
  fi

  printf '%s\n' "$devices" | head -1
}