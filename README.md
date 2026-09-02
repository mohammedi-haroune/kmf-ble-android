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

The POSIX Gradle wrapper loads the project-local Java home from `gradle/jdk.env` when `JAVA_HOME` is not already set:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

Create the wrapper if needed:

```bash
gradle wrapper --gradle-version 8.13
```

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Or use the root `Makefile`:

```bash
make build
make install
make run
make live
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

Install the debug APK on a connected Android device:

```bash
adb devices
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Battery History and CSV Export

The app keeps timestamped battery samples locally until you clear them from the **History** tab. Samples include voltage, current, power, charging state, remaining and capacity Ah, SoC, and charge/discharge energy totals. It does not persist raw BLE frame events for this history.

Tap **Export CSV** in the History tab, then extract the private export to the Mac with ADB:

```bash
adb exec-out run-as com.juncehome.lifepo4ble cat files/exports/battery-history.csv > battery-history.csv
```

The exporter pages through the database, so it does not load the full retained history into memory. The CSV has a UTC ISO-8601 timestamp and epoch milliseconds in every row.

## Mac analytics dashboard

The `analytics/` Streamlit dashboard reads `~/Downloads/battery-history.csv` by default and also accepts an uploaded CSV from its sidebar. It provides date/time/state filters, raw-to-daily grouping, interactive battery charts, daily power analysis, and a filtered CSV download.

```bash
python3 -m venv analytics/.venv
analytics/.venv/bin/pip install -r analytics/requirements.txt
analytics/.venv/bin/streamlit run analytics/app.py --server.address 127.0.0.1
```

Open `http://127.0.0.1:8501`. V1 is Mac-local only; it does not expose the dashboard to the network or synchronize data from the Android app.

## Meter Validation

Record real-device findings in `docs/meter-protocol-notes.md`. Keep MAC addresses and sensitive observations redacted unless explicitly intended for sharing.
