#!/usr/bin/env bash
#
# Build the DEBUG APK.
# Output: app/build/outputs/apk/debug/app-debug.apk
#
# Usage: scripts/build-debug.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

JAVA_HOME="$(resolve_java_home)"
[[ -n "$JAVA_HOME" ]] || die "No compatible JDK (17-21) found. Install one, e.g. 'brew install openjdk@17'."
export JAVA_HOME
log "Using JDK: $JAVA_HOME"

log "Building debug APK..."
( cd "$PROJECT_ROOT" && "$GRADLEW" :app:assembleDebug )

APK="$APP_APK_DIR/debug/app-debug.apk"
[[ -f "$APK" ]] || die "Build finished but APK not found at $APK"
log "Debug APK ready: $APK"