#!/usr/bin/env bash
#
# Build the PRODUCTION (release) APK.
#
# The app has no release signing config, so Gradle emits an *unsigned* release
# APK. An unsigned APK cannot be installed on a device, so this script signs it
# so it is installable. Signing key selection:
#
#   * If you provide a real release keystore via env vars, it is used:
#         RELEASE_KEYSTORE=/path/to/my-release.jks
#         RELEASE_KEY_ALIAS=my-alias
#         RELEASE_STORE_PASSWORD=...        (prompted if unset)
#         RELEASE_KEY_PASSWORD=...          (defaults to store password)
#   * Otherwise it falls back to the Android debug keystore
#     (~/.android/debug.keystore), which is fine for testing on your own phone
#     but NOT acceptable for Google Play / distribution to others.
#
# Output: app/build/outputs/apk/release/app-release.apk  (signed)
#
# Usage: scripts/build-release.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

JAVA_HOME="$(resolve_java_home)"
[[ -n "$JAVA_HOME" ]] || die "No compatible JDK (17-21) found. Install one, e.g. 'brew install openjdk@17'."
export JAVA_HOME
log "Using JDK: $JAVA_HOME"

log "Building release APK..."
( cd "$PROJECT_ROOT" && "$GRADLEW" :app:assembleRelease )

RELEASE_DIR="$APP_APK_DIR/release"
SIGNED_APK="$RELEASE_DIR/app-release.apk"

# Gradle names the artifact app-release.apk when a signing config exists, or
# app-release-unsigned.apk otherwise. Find whichever was produced.
UNSIGNED_APK="$RELEASE_DIR/app-release-unsigned.apk"
if [[ -f "$SIGNED_APK" ]]; then
  log "Gradle produced a signed release APK: $SIGNED_APK"
  exit 0
fi
[[ -f "$UNSIGNED_APK" ]] || die "Release build finished but no APK found in $RELEASE_DIR"

# --- Sign it -----------------------------------------------------------------
BUILD_TOOLS="$(resolve_build_tools)"
[[ -n "$BUILD_TOOLS" && -x "$BUILD_TOOLS/apksigner" ]] || \
  die "apksigner not found. Install Android build-tools via the SDK manager."
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"

if [[ -n "${RELEASE_KEYSTORE:-}" ]]; then
  KEYSTORE="$RELEASE_KEYSTORE"
  KEY_ALIAS="${RELEASE_KEY_ALIAS:?Set RELEASE_KEY_ALIAS when using RELEASE_KEYSTORE}"
  if [[ -z "${RELEASE_STORE_PASSWORD:-}" ]]; then
    read -r -s -p "Keystore password: " RELEASE_STORE_PASSWORD; echo
  fi
  STORE_PASS="$RELEASE_STORE_PASSWORD"
  KEY_PASS="${RELEASE_KEY_PASSWORD:-$RELEASE_STORE_PASSWORD}"
  log "Signing with release keystore: $KEYSTORE (alias: $KEY_ALIAS)"
else
  warn "No RELEASE_KEYSTORE set — signing with the DEBUG keystore (testing only, not for distribution)."
  KEYSTORE="$HOME/.android/debug.keystore"
  KEY_ALIAS="androiddebugkey"
  STORE_PASS="android"
  KEY_PASS="android"
  if [[ ! -f "$KEYSTORE" ]]; then
    log "Debug keystore missing; creating $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$JAVA_HOME/bin/keytool" -genkeypair -v \
      -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
      -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US"
  fi
fi

ALIGNED_APK="$RELEASE_DIR/app-release-aligned.apk"
if [[ -x "$ZIPALIGN" ]]; then
  log "Aligning APK..."
  "$ZIPALIGN" -f -p 4 "$UNSIGNED_APK" "$ALIGNED_APK"
else
  cp "$UNSIGNED_APK" "$ALIGNED_APK"
fi

log "Signing APK..."
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$SIGNED_APK" \
  "$ALIGNED_APK"

rm -f "$ALIGNED_APK" "$SIGNED_APK.idsig"
"$APKSIGNER" verify "$SIGNED_APK" >/dev/null && log "Signature verified."
log "Release APK ready: $SIGNED_APK"
