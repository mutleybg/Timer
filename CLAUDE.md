# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A minimal Android **Timer** app written in **Java** with **XML layouts + AndroidX Views** (not Kotlin, not Compose), built with **Gradle**. It shares its stack, build infrastructure, and core architecture with the sibling `../AndroidAlarm` project — the alarm app is the reference implementation for anything not obvious here.

The user picks a number of minutes (0–59) and taps Start; the app counts down and, when the time is up, rings a bundled sound (`res/raw/timer_sound.mp3`). Start and Stop are mutually exclusive (exactly one enabled). The timer fires and rings even if the app is closed or the device rebooted.

## Build & run

Requires an Android SDK and a **JDK 17–21** (the code targets Java 17; Gradle here rejects newer JDKs).

Gradle directly (needs `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties`, and a JDK 17–21 as `JAVA_HOME`):

```bash
./gradlew :app:assembleDebug      # build debug APK
./gradlew testDebugUnitTest       # run JVM unit tests
./gradlew installDebug            # install on a connected device
./gradlew testDebugUnitTest --tests 'com.example.timer.ExampleUnitTest'   # single test class
```

Prefer the helper scripts in `scripts/` — they **auto-select a compatible JDK** (17–21) for that process only without touching your global `JAVA_HOME`, and the deploy scripts **auto-discover the connected phone** via `adb`:

```bash
./scripts/build-debug.sh          # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./scripts/build-release.sh        # signed release APK (see signing note below)
./scripts/deploy-debug.sh         # build + install + launch on the phone
./scripts/deploy-release.sh       # same, release build
SKIP_BUILD=1 ./scripts/deploy-debug.sh    # install an already-built APK
REINSTALL=1  ./scripts/deploy-release.sh  # uninstall first (fixes signature clashes; wipes app data)
```

Multiple devices attached: pass a serial as `$1` or set `ANDROID_SERIAL`; otherwise a non-emulator device is preferred.

## Ring/scheduling flow (the heart of the app)

Countdown, sound, and "survives app close" are handled the same way AndroidAlarm handles alarms — via `AlarmManager` + a foreground service, **not** an in-process timer (an in-process `Handler`/`CountDownTimer` dies when the app is killed):

1. **`TimerScheduler`** (all static) persists state to `SharedPreferences` (`timer_prefs`: minutes, running flag, and the **absolute** trigger time `now + minutes`) and arms an exact alarm via `AlarmManager.setAlarmClock` (survives Doze, shows in the status bar). Persisting the absolute trigger time is what lets `BootReceiver` re-arm a running timer after a reboot.
2. When due, **`TimerReceiver`** (a `BroadcastReceiver`) marks the timer finished and starts **`TimerService`**.
3. **`TimerService`** is a foreground service (`mediaPlayback` type) that owns the ring lifecycle: it loops the sound on the **alarm audio stream**, and posts a high-priority notification with a **full-screen intent** to `TimerRingActivity` (the sanctioned way to show UI over the lock screen from the background). It rings for up to 1 minute (`RING_TIMEOUT_MS`); if left unconfirmed it schedules **one** automatic re-ring a minute later (a separate PendingIntent carrying `EXTRA_RERUN=1`, so `TimerReceiver` skips `onFired` on the re-ring).
4. **Power button stops the ring:** apps can't observe `KEYCODE_POWER`, so `TimerService` registers an `ACTION_SCREEN_OFF` receiver and treats screen-off exactly like tapping Stop. `TimerRingActivity` keeps the screen on (`FLAG_KEEP_SCREEN_ON`) so an actual screen-off is unambiguously a power press.

**If you change the ring, re-ring, or power-button behavior, keep it in `TimerService`** — the service, not the activity, owns it, so it works whether or not the ring screen is on top. The two `Intent` actions between the ring UI and the service are `TimerService.ACTION_DISMISS` (stop everything, cancel re-ring) and `TimerRingActivity.ACTION_FINISH` (close the ring screen).

**`MainActivity`** drives the UI: the minutes `NumberPicker`, the mutually-exclusive Start/Stop buttons, a per-second countdown label (a `Handler` ticker, foreground-only), the two permission gates (notifications on 13+, exact-alarm on 12+), and the one-time reliability hint (Autostart / battery-optimization dialog) shown on the first Start.

## Architecture

- **`scripts/common.sh`** is the shared foundation for all build/deploy scripts (source it, don't run it). It resolves the JDK, Android SDK, `adb`, and newest `build-tools`, and owns device discovery (`pick_device`, `list_ready_devices`). Changes to how devices/tools are located belong here.

- **Release signing is done by the script, not Gradle.** `app/build.gradle` has no release signing config, so `assembleRelease` emits an *unsigned* APK. `build-release.sh` then zipaligns and signs it with `apksigner`. By default it falls back to the Android **debug keystore** (testing only — not for Play distribution); provide a real key via `RELEASE_KEYSTORE` / `RELEASE_KEY_ALIAS` / `RELEASE_STORE_PASSWORD` env vars for a distributable build.

- **Forced Bulgarian locale.** Every activity extends `BaseActivity`, which overrides `attachBaseContext` to wrap the context via `LocaleHelper.wrap` — pinning the UI to Bulgarian (`bg`) regardless of device locale. **New activities must extend `BaseActivity`**, not `AppCompatActivity` directly, or they'll render in the device language. User-facing strings live in `res/values-bg/strings.xml`; `res/values/strings.xml` is the default fallback.

- **View binding** is enabled (`buildFeatures.viewBinding true`). Access views through the generated `*Binding` classes (e.g. `ActivityMainBinding`) rather than `findViewById`.

## Conventions

- `applicationId` / `namespace`: `com.example.timer`. `minSdk 26`, `targetSdk`/`compileSdk 34`.
- Keep the Java + XML Views stack; do not introduce Kotlin or Compose.