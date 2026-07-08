# KMF BLE Android

Native Android BLE utility for a KMF LiFePO4 meter.

## Scope

- Kotlin + Jetpack Compose.
- BLE-only foreground app.
- Scans, connects, discovers a safe notify/write GATT profile, enables CCCD notifications, logs packets, parses `A=` and `C=` KMF frames, and polls totals with `:C\n`.
- No MQTT, sockets, Wi-Fi setup, cloud sync, background service, or Flutter.

## Android Requirements

- Minimum SDK: 24.
- Target SDK: 35.
- Device must support Bluetooth LE.
- API 24-30 runtime permission: fine location for BLE scanning.
- API 31+ runtime permissions: Bluetooth scan and Bluetooth connect.
- The manifest currently uses `BLUETOOTH_SCAN` with `neverForLocation`; validate this with a real meter before production use.

## Development

Create the wrapper if needed:

```bash
gradle wrapper --gradle-version 8.13
```

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Run JVM unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Run focused protocol tests:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.protocol.*'
```

Run focused BLE tests:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ble.*'
```

## Meter Validation

Record real-device findings in `docs/meter-protocol-notes.md`. Keep MAC addresses and sensitive observations redacted unless explicitly intended for sharing.
