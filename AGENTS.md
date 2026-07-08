# Repository Guidelines

## Project Structure & Module Organization

This repository is currently centered on `kmf.yml`, the ESPHome BLE behavior reference, and `01-plan.md`, the implementation plan for the native Android app. Follow the file map in `01-plan.md` when scaffolding the app: root Gradle files (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`), the Android module in `app/`, Kotlin sources under `app/src/main/java/com/juncehome/lifepo4ble/`, and unit tests under `app/src/test/java/com/juncehome/lifepo4ble/`.

Keep boundaries clear: `platform/` for permission policy, `protocol/` for KMF parsing and packet logging, `ble/` for scanning/GATT/session code, `data/` for DataStore persistence, and `ui/` for ViewModel and Compose screens.

## Build, Test, and Development Commands

- `gradle wrapper --gradle-version 8.13`: creates the Gradle wrapper during initial scaffold.
- `./gradlew :app:assembleDebug`: builds the debug Android APK.
- `./gradlew :app:testDebugUnitTest`: runs JVM unit tests.
- `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.protocol.*'`: runs focused protocol tests.

If `gradlew` does not exist yet, create it before relying on project-local Gradle commands.

## Coding Style & Naming Conventions

Use Kotlin with Jetpack Compose, Material3, Coroutines, Flow/StateFlow, and manual dependency wiring. Source files and identifiers should remain ASCII-only. Prefer small, focused classes with names matching their responsibility, such as `BlePermissionPolicy`, `KmfLineParser`, `GattOperationQueue`, and `BleViewModel`.

Do not hardcode observed KMF MAC addresses, service UUIDs, or characteristic UUIDs in app logic. Treat `kmf.yml` as a behavior reference, not an identifier source.

## Testing Guidelines

Use JUnit4, `kotlinx-coroutines-test`, and AndroidX test APIs for JVM tests. Name tests `*Test.kt` and place them beside the matching package under `app/src/test/java/`. Prioritize pure Kotlin coverage for permission policy, protocol parsing, packet formatting, profile selection, GATT queue behavior, reducers, and ViewModel state.

## Commit & Pull Request Guidelines

The plan uses Conventional Commit-style messages, for example `chore: bootstrap native android ble app`, `feat: add kmf protocol parser`, and `docs: document kmf ble validation`. Keep commits scoped to one task or behavior change.

Pull requests should describe the implemented task, list verification commands run, call out BLE device validation results when relevant, and include screenshots for Compose UI changes.

## Security & Configuration Tips

Keep Wi-Fi credentials, API keys, device addresses, and real meter observations out of commits unless explicitly redacted. Store validation notes in `docs/meter-protocol-notes.md` with sensitive values removed.
