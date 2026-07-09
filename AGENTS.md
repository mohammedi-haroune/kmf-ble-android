# Repository Guidelines

## Project Structure & Module Organization

This repository is currently centered on `planning/INDEX.md`, which points to the active planning documents. The current ESPHome BLE behavior reference lives at `planning/resources/kmf.yml`, and the implementation plan for the native Android app lives at `planning/plans/01-plan.md`. Follow the file map in `planning/plans/01-plan.md` when scaffolding the app: root Gradle files (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`), the Android module in `app/`, Kotlin sources under `app/src/main/java/com/juncehome/lifepo4ble/`, and unit tests under `app/src/test/java/com/juncehome/lifepo4ble/`.

Keep boundaries clear: `platform/` for permission policy, `protocol/` for KMF parsing and packet logging, `ble/` for scanning/GATT/session code, `data/` for DataStore persistence, and `ui/` for ViewModel and Compose screens.

## Build, Test, and Development Commands

POSIX `./gradlew` commands load the repo-level JDK setting from `gradle/jdk.env` when `JAVA_HOME` is not already set: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.

- `gradle wrapper --gradle-version 8.13`: creates the Gradle wrapper during initial scaffold.
- `./gradlew :app:assembleDebug`: builds the debug Android APK.
- `./gradlew :app:testDebugUnitTest`: runs JVM unit tests.
- `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.protocol.*'`: runs focused protocol tests.
- `adb devices`: confirms a device is connected and authorized.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: installs the debug APK after `:app:assembleDebug`.
- `adb shell am start -n com.juncehome.lifepo4ble/.MainActivity`: launches the installed debug app for end-to-end validation.
- `adb shell pidof -s com.juncehome.lifepo4ble`: gets the current app PID for log filtering.
- `adb logcat --pid "$(adb shell pidof -s com.juncehome.lifepo4ble)" -v threadtime`: preferred live app-log stream when the app is running.
- `adb logcat -d -s KMF-BLE`: preferred buffered app-log dump by app tag when PID filtering is not enough.
- `adb shell run-as com.juncehome.lifepo4ble cat files/kmf-ble.log`: reads the app-owned persistent log file captured by `AppLog`.

If `gradlew` does not exist yet, create it before relying on project-local Gradle commands.

## Coding Style & Naming Conventions

Use Kotlin with Jetpack Compose, Material3, Coroutines, Flow/StateFlow, and manual dependency wiring. Source files and identifiers should remain ASCII-only. Prefer small, focused classes with names matching their responsibility, such as `BlePermissionPolicy`, `KmfLineParser`, `GattOperationQueue`, and `BleViewModel`.

Do not hardcode observed KMF MAC addresses, service UUIDs, or characteristic UUIDs in app logic. Treat `planning/resources/kmf.yml` as a behavior reference, not an identifier source.
Add as much useful logging as practical around BLE discovery, connection state, GATT operations, parsing, persistence, and UI state transitions so failures can be debugged from logs alone.
For log analysis tasks, prefer this app's own logs over broad device logs: first use PID-filtered `adb logcat` for `com.juncehome.lifepo4ble`, then tag-filtered `KMF-BLE` output, then the app file `files/kmf-ble.log` via `run-as`. Only widen to generic device logs when app logs do not explain the behavior.

## KMF BLE Behavior Notes

Current repo behavior should match the official Android app's BLE startup sequence more closely than the earlier one-shot bootstrap implementation.

- The official KMF BLE page enables notifications first, then requests `MTU 100` on Android, then starts sending `:*` over BLE.
- The official app does not stop after one startup write. It retries `:*` roughly every 2 seconds until both `A` and `B` data sets are present in its parsed state.
- Only after the meter is actively producing the richer state should this app rely on slower follow-up polling such as `:C\n`.
- Treat `:B=` frames as real protocol state, not parser noise. The official app's readiness condition depends on both `A` and `B`.
- A useful failure signature is: this app shows correct `Capacity Ah` / `SoC` only while the official app is simultaneously connected. That means the meter is being actively driven into richer output by ongoing BLE startup traffic, not by a one-time persistent device-side mode change.
- Keep KMF startup control above the raw GATT layer when possible so parsed protocol state can decide whether to keep sending `:*` or stop.

## Testing Guidelines

Use JUnit4, `kotlinx-coroutines-test`, and AndroidX test APIs for JVM tests. Name tests `*Test.kt` and place them beside the matching package under `app/src/test/java/`. Prioritize pure Kotlin coverage for permission policy, protocol parsing, packet formatting, profile selection, GATT queue behavior, reducers, and ViewModel state.
Treat a USB-connected Android device as part of the default verification path, not an optional extra. For any runnable Android change, finish with an on-device end-to-end pass: run `adb devices`, build the debug app, install it on the connected device, launch it, and inspect the app logs (`--pid`, `KMF-BLE`, and `files/kmf-ble.log`) to confirm the app is behaving as expected. Only skip this when the change is truly non-runnable or the device/ADB path is unavailable, and in that case report the exact blocker explicitly.

For KMF protocol changes, verify both levels:

- JVM tests for parser/reducer/startup-loop behavior.
- On-device validation that `Capacity Ah` and `SoC` appear without the official app being open at the same time.
- The full install-launch-log pass on the connected device, with any BLE behavior regression called out explicitly.

## Commit & Pull Request Guidelines

The plan uses Conventional Commit-style messages, for example `chore: bootstrap native android ble app`, `feat: add kmf protocol parser`, and `docs: document kmf ble validation`. Keep commits scoped to one task or behavior change.

Pull requests should describe the implemented task, list verification commands run, call out BLE device validation results when relevant, and include screenshots for Compose UI changes.
Include the final install-launch-log verification in the reported validation steps whenever the app is runnable. If that device pass was not run, state the exact reason instead of omitting it.

## Security & Configuration Tips

Keep Wi-Fi credentials, API keys, device addresses, and real meter observations out of commits unless explicitly redacted. Store validation notes in `docs/meter-protocol-notes.md` with sensitive values removed.
