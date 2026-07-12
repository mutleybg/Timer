# Timer

A minimal Android timer app, written in **Java** with **XML layouts + AndroidX
Views** (not Kotlin/Compose) and built with **Gradle** — same stack and build
infrastructure as the sibling AndroidAlarm project. The UI is forced to
Bulgarian regardless of device locale.

This repository currently contains only the project **scaffold**; the timer
functionality will be added later.

## Build

The build needs an Android SDK and a JDK **17–21**.

- **Android Studio:** open the project folder and Run/Debug.
- **Command line** (requires `ANDROID_HOME` or a `local.properties` with
  `sdk.dir=...`, and `JAVA_HOME` on JDK 17):
  - Build debug APK: `./gradlew :app:assembleDebug`
  - Unit tests: `./gradlew testDebugUnitTest`
  - Install to device: `./gradlew installDebug`

## Build & deploy scripts

Helper scripts live in [`scripts/`](scripts/). They pick a compatible JDK
(17–21) automatically without touching your global `JAVA_HOME`, and the deploy
scripts **auto-discover the connected phone** via `adb`.

| Script | What it does | Output |
| --- | --- | --- |
| `scripts/build-debug.sh` | Builds the debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| `scripts/build-release.sh` | Builds and signs the release APK | `app/build/outputs/apk/release/app-release.apk` |
| `scripts/deploy-debug.sh` | Builds, installs the debug APK, and launches it | — |
| `scripts/deploy-release.sh` | Builds, installs the release APK, and launches it | — |

```bash
# Build only
./scripts/build-debug.sh

# Build + install + launch on the connected phone
./scripts/deploy-debug.sh

# Install an already-built APK without rebuilding
SKIP_BUILD=1 ./scripts/deploy-debug.sh
```
