# BLE-Only Core Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Kotlin + Jetpack Compose Android app that connects to a KMF meter over BLE, discovers the correct data characteristic at runtime, subscribes to notifications, polls the meter totals command, and displays live KMF readings plus packet logs.

**Project Root:** `/Users/mac/Workspace/junce-home/kmf-ble-android`

**Architecture:** The app is a single-activity native Android app. It has four boundaries: Android permission/Bluetooth readiness, BLE transport with a serialized GATT operation queue, KMF protocol parsing based on `kmf.yml`, and ViewModel/Compose UI state. `kmf.yml` is the behavior reference for how to receive and parse KMF BLE data: notify on the data characteristic, buffer text until CR/LF, parse `A=` and `C=` lines, discard oversized fragments, and periodically write `:C\n`; do not copy its MAC address or UUIDs as app constants.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose, Material3, Coroutines, Flow/StateFlow, BluetoothLeScanner/BluetoothGatt, DataStore Preferences, JUnit4, kotlinx-coroutines-test, AndroidX test APIs.

## Global Constraints

- `minSdk = 24`
- `targetSdk = 35`
- `compileSdk = 35`
- Native Android only; no Flutter integration in this plan.
- Single-activity, Compose-only UI.
- Manual dependency wiring only; no Hilt or Koin in v1.
- BLE only; no MQTT, sockets, Wi-Fi setup, LAN, WAN, or cloud sync.
- Foreground-only; no background service.
- No hardcoded KMF MAC address, service UUID, or characteristic UUID in app logic.
- `kmf.yml` is the authoritative behavior reference for KMF BLE data flow, not an identifier source.
- Show selected device, connection state, selected service UUID, notify UUID, write UUID, and live packet log.
- Log inbound notifications and outbound writes with timestamp, direction, hex, ASCII, and byte length.
- Persist only last connected device address/name and last successful UUID selection in DataStore.
- Bound the in-memory packet log to 200 entries.
- Treat malformed BLE fragments as non-fatal; discard or log them, but never crash the session because of bad bytes.
- All GATT descriptor and characteristic writes must be serialized through one operation queue.
- Start KMF polling only after notification subscription is fully enabled at the CCCD descriptor.
- ASCII-only source files and identifiers.

## Source References

- `kmf.yml`
  - Uses a BLE client characteristic with `notify: true`.
  - Buffers incoming bytes until `\r` or `\n`.
  - Detects `A=` and `C=` anywhere in the complete text line.
  - Drops the receive buffer when it grows past 200 bytes.
  - Sends bytes `[0x3A, 0x43, 0x0A]`, which is ASCII `:C\n`, every 30 seconds to request energy totals.
  - The MAC address and UUIDs in this file are reference observations only and must not be hardcoded in the Android app.
- `../output/resources/com.juntek.platform.apk/assets/apps/__UNI__D0FA0D1/www/app-service.js`
  - Useful only to understand legacy behavior. It chooses services/characteristics by position and retries aggressively; the new app must not copy that selection strategy as the only strategy.
- `../output/sources/io/dcloud/feature/bluetooth/BluetoothBaseAdapter.java`
  - Confirms Android GATT service and characteristic enumeration, CCCD descriptor writes for notify/indicate, and callback-driven characteristic changes.

## Android BLE Permission Matrix

Manifest must declare:

```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Runtime permission policy:

- API 24-30: request `ACCESS_FINE_LOCATION` before BLE scanning; do not request `BLUETOOTH_SCAN` or `BLUETOOTH_CONNECT` because they do not exist on these releases. Surface a Location Services disabled state if scans return empty on devices that gate BLE scanning behind the system location toggle.
- API 31+: request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- Validate on a real KMF meter that scanning works with `neverForLocation`. If the meter is filtered, remove `neverForLocation`, add a clear location disclosure, and request location as required by Android policy.
- Always check the adapter exists, BLE feature exists, Bluetooth is enabled, and required runtime permissions are granted before starting a scan or connection.

## KMF BLE Data Contract

Use `kmf.yml` as the source of truth for v1 data behavior:

- Subscribe to the selected notify or indicate characteristic.
- Accumulate notification bytes as ASCII text.
- A complete line ends at `\r` or `\n`.
- `A=` frames contain at least six integer fields:
  - voltage volts = `field0 / 100`
  - current magnitude amps = `field1 / 1000`
  - charging = `field2 == 1`
  - minutes remaining = `field3`
  - remaining Ah = `field4 / 1000`
  - capacity Ah = `field5 / 10`
  - signed current is positive when charging and negative when discharging
  - power watts = `field0 * field1 / 100000`, signed the same way as current
  - SoC percent = clamp(`remainingAh / capacityAh * 100`, 0, 100)
- `C=` frames contain at least two integer fields:
  - charge kWh = `field0 / 1000`
  - discharge kWh = `field1 / 1000`
- Send `:C\n` every 30 seconds after notification subscription succeeds.
- Do not implement `:A=`, `:B=`, Wi-Fi setup, password, firmware, history, MQTT, or socket commands in this plan.

## File Map

- `settings.gradle.kts`: root module wiring.
- `build.gradle.kts`: shared Gradle plugin configuration.
- `gradle/libs.versions.toml`: dependency catalog.
- `app/build.gradle.kts`: app module setup.
- `app/src/main/AndroidManifest.xml`: BLE feature, permissions, activity, and application declarations.
- `app/src/main/java/com/juncehome/lifepo4ble/BleApplication.kt`: application graph owner.
- `app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt`: manual dependency wiring.
- `app/src/main/java/com/juncehome/lifepo4ble/MainActivity.kt`: permission and Bluetooth readiness gate.
- `app/src/main/java/com/juncehome/lifepo4ble/App.kt`: Compose root.
- `app/src/main/java/com/juncehome/lifepo4ble/platform/BlePermissionPolicy.kt`: pure permission matrix.
- `app/src/main/java/com/juncehome/lifepo4ble/protocol/*`: packet log formatting, KMF parser, KMF frame models, reading merger.
- `app/src/main/java/com/juncehome/lifepo4ble/ble/*`: scanner, session, GATT operation queue, selector, repository, Android adapters.
- `app/src/main/java/com/juncehome/lifepo4ble/data/*`: DataStore-backed last-device persistence.
- `app/src/main/java/com/juncehome/lifepo4ble/ui/*`: UI state, reducer, ViewModel, and Compose screen.
- `docs/meter-protocol-notes.md`: real-meter observations with MAC address redacted by default.
- `app/src/test/java/com/juncehome/lifepo4ble/**/*Test.kt`: JVM unit tests for pure Kotlin behavior.

## Acceptance Criteria

- The app can scan for nearby BLE devices after the correct API-specific permissions are granted.
- The app can connect to the meter, discover services, choose a usable data profile, enable notifications through the CCCD descriptor, and show connected state.
- The app displays discovered service UUIDs and selected notify/write UUIDs.
- Incoming notifications are logged and parsed as line-delimited `A=` and `C=` KMF frames.
- The app displays voltage, signed current, signed power, charging status, remaining minutes, remaining Ah, capacity Ah, SoC percent, charge kWh, and discharge kWh.
- The app writes and logs `:C\n` every 30 seconds after notification setup succeeds.
- The last successful device address/name and UUID selection survive app restart.
- Disconnect, permission denial, Bluetooth-off state, malformed bytes, failed descriptor writes, and failed characteristic writes produce visible non-fatal UI errors.

### Task 1: Scaffold Native Android App and Permission Policy

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/BleApplication.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/MainActivity.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/App.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/platform/BlePermissionPolicy.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/theme/Color.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/theme/Type.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/platform/BlePermissionPolicyTest.kt`

**Interfaces:**
- `BleApplication` owns a single `AppGraph`.
- `AppGraph` wires dependencies manually.
- `BlePermissionPolicy.requiredRuntimePermissions(sdkInt: Int): Set<String>` returns the runtime permissions needed before BLE operations.
- `MainActivity` checks runtime permissions, BLE support, adapter presence, and adapter enabled state before rendering scan/connect controls.

- [ ] **Step 1: Bootstrap Gradle wrapper in the project root**

Run from `/Users/mac/Workspace/junce-home/kmf-ble-android`:

```bash
gradle wrapper --gradle-version 8.13
```

Expected: `gradlew` exists. If `gradle` is not installed, create the wrapper once with Android Studio or install a local Gradle distribution before continuing.

- [ ] **Step 2: Write failing permission policy test**

```kotlin
class BlePermissionPolicyTest {
    @Test
    fun api30RequiresFineLocationOnlyAtRuntime() {
        assertEquals(
            setOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.requiredRuntimePermissions(30)
        )
    }

    @Test
    fun api31RequiresBluetoothScanAndConnectAtRuntime() {
        assertEquals(
            setOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ),
            BlePermissionPolicy.requiredRuntimePermissions(31)
        )
    }
}
```

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.juncehome.lifepo4ble.platform.BlePermissionPolicyTest
```

Expected: fails because the project and policy do not exist yet.

- [ ] **Step 3: Create minimal app and permission policy**

Implement `BlePermissionPolicy` exactly:

```kotlin
object BlePermissionPolicy {
    fun requiredRuntimePermissions(sdkInt: Int): Set<String> =
        if (sdkInt >= 31) {
            setOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            setOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
```

Create the manifest with the permission matrix from this plan. Create a minimal Compose screen showing:

- permission status
- Bluetooth enabled status
- placeholder text: `KMF BLE`

- [ ] **Step 4: Run build and policy tests**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: build and tests pass.

- [ ] **Step 5: Commit when inside a Git repository**

```bash
git rev-parse --is-inside-work-tree
```

If the command prints `true`, run:

```bash
git add .
git commit -m "chore: bootstrap native android ble app"
```

If it fails or prints anything else, skip the commit and continue.

### Task 2: Implement KMF Protocol Parser and Packet Logging

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/PacketDirection.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/FrameLogEntry.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/PacketFormatter.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/KmfFrame.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/KmfReading.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/KmfLineParser.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/KmfReadingMerger.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/protocol/PacketFormatterTest.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/protocol/KmfLineParserTest.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/protocol/KmfReadingMergerTest.kt`

**Interfaces:**
- `PacketDirection` has `INBOUND` and `OUTBOUND`.
- `FrameLogEntry(timestampMs: Long, direction: PacketDirection, hex: String, ascii: String, length: Int)`.
- `PacketFormatter.toEntry(timestampMs, direction, bytes)` returns log entries with space-separated uppercase hex and printable ASCII.
- `KmfLineParser.offer(bytes: ByteArray): List<KmfFrame>` buffers bytes until CR/LF, accepts `A=` or `C=` anywhere in the line, and clears the buffer after 200 bytes.
- `KmfReadingMerger.apply(reading: KmfReading, frame: KmfFrame): KmfReading` merges partial `A` and `C` updates without erasing previous values.

- [ ] **Step 1: Write failing formatter test**

```kotlin
@Test
fun formatsBytesForLogDisplay() {
    val entry = PacketFormatter.toEntry(
        timestampMs = 123L,
        direction = PacketDirection.INBOUND,
        bytes = byteArrayOf(0x41, 0x42, 0x00, 0x10)
    )

    assertEquals("41 42 00 10", entry.hex)
    assertEquals("AB..", entry.ascii)
    assertEquals(4, entry.length)
}
```

- [ ] **Step 2: Write failing parser tests from `kmf.yml` behavior**

```kotlin
@Test
fun parsesAFrameAcrossNotificationBoundaries() {
    val parser = KmfLineParser()

    assertTrue(parser.offer(":A=1234,2500,1,120,2400".encodeToByteArray()).isEmpty())
    val frames = parser.offer(",1000\r\n".encodeToByteArray())

    val frame = frames.single() as KmfFrame.A
    assertEquals(12.34, frame.voltageV, 0.001)
    assertEquals(2.5, frame.currentA, 0.001)
    assertEquals(30.85, frame.powerW, 0.001)
    assertTrue(frame.charging)
    assertEquals(120, frame.minutesRemaining)
    assertEquals(2.4, frame.remainingAh, 0.001)
    assertEquals(100.0, frame.capacityAh, 0.001)
    assertEquals(2.4, frame.socPercent, 0.001)
}

@Test
fun parsesCFrameAndKeepsLineParserNonFatal() {
    val parser = KmfLineParser()

    val frames = parser.offer("noise:C=12,34\n".encodeToByteArray())

    val frame = frames.single() as KmfFrame.C
    assertEquals(0.012, frame.chargeKwh, 0.001)
    assertEquals(0.034, frame.dischargeKwh, 0.001)
}

@Test
fun discardsOversizedLineWithoutThrowing() {
    val parser = KmfLineParser(maxLineChars = 200)

    val frames = parser.offer(("A=" + "1".repeat(250) + "\n").encodeToByteArray())

    assertTrue(frames.isEmpty())
}
```

- [ ] **Step 3: Write failing merge test**

```kotlin
@Test
fun cFrameDoesNotEraseLatestAReading() {
    val afterA = KmfReadingMerger.apply(
        KmfReading(),
        KmfFrame.A(
            voltageV = 12.34,
            currentA = 2.5,
            powerW = 30.85,
            charging = true,
            minutesRemaining = 120,
            remainingAh = 2.4,
            capacityAh = 100.0,
            socPercent = 2.4,
            status = "Charging",
        )
    )

    val afterC = KmfReadingMerger.apply(
        afterA,
        KmfFrame.C(chargeKwh = 0.012, dischargeKwh = 0.034)
    )

    assertEquals(12.34, afterC.voltageV, 0.001)
    assertEquals(0.012, afterC.chargeKwh, 0.001)
    assertEquals(0.034, afterC.dischargeKwh, 0.001)
}
```

- [ ] **Step 4: Implement protocol types and parser**

Use `String(bytes, Charsets.US_ASCII)` for text conversion. Treat malformed integers, missing fields, and unknown lines as ignored input. Do not throw from `offer`.

- [ ] **Step 5: Run protocol tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.protocol.*'
```

Expected: protocol tests pass.

- [ ] **Step 6: Commit when inside a Git repository**

```bash
git rev-parse --is-inside-work-tree
```

If true:

```bash
git add .
git commit -m "feat: add kmf protocol parser"
```

### Task 3: Add GATT Profile Discovery, Selection, and Persistence Models

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/GattCharacteristicInfo.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/GattServiceInfo.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/GattProfile.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/GattProfileSelector.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/data/DeviceSnapshot.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/data/DeviceStore.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/ble/GattProfileSelectorTest.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/data/DeviceStoreTest.kt`

**Interfaces:**
- `GattCharacteristicInfo(uuid: UUID, canNotify: Boolean, canIndicate: Boolean, canRead: Boolean, canWrite: Boolean, canWriteNoResponse: Boolean, hasClientConfigDescriptor: Boolean)`.
- `GattServiceInfo(uuid: UUID, characteristics: List<GattCharacteristicInfo>)`.
- `GattProfile(serviceUuid: UUID, notifyUuid: UUID, writeUuid: UUID, usesIndications: Boolean)`.
- `GattProfileSelector.select(services: List<GattServiceInfo>, preferred: DeviceSnapshot?): GattProfile?`.
- `DeviceSnapshot(address: String, name: String?, serviceUuid: String?, notifyUuid: String?, writeUuid: String?)`.
- `DeviceStore.snapshot: Flow<DeviceSnapshot?>`, `save(snapshot)`, and `clear()`.

- [ ] **Step 1: Write failing selector tests**

```kotlin
@Test
fun prefersPersistedProfileWhenStillValid() {
    val serviceUuid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val dataUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val service = GattServiceInfo(
        uuid = serviceUuid,
        characteristics = listOf(
            GattCharacteristicInfo(
                uuid = dataUuid,
                canNotify = true,
                canIndicate = false,
                canRead = true,
                canWrite = true,
                canWriteNoResponse = false,
                hasClientConfigDescriptor = true,
            )
        )
    )
    val snapshot = DeviceSnapshot(
        address = "AA:BB:CC:DD:EE:FF",
        name = "KMF",
        serviceUuid = serviceUuid.toString(),
        notifyUuid = dataUuid.toString(),
        writeUuid = dataUuid.toString(),
    )

    val profile = GattProfileSelector.select(listOf(service), snapshot)

    assertEquals(serviceUuid, profile?.serviceUuid)
    assertEquals(dataUuid, profile?.notifyUuid)
    assertEquals(dataUuid, profile?.writeUuid)
}

@Test
fun returnsNullWhenNoNotifyOrIndicateCharacteristicHasCccd() {
    val service = GattServiceInfo(
        uuid = UUID.randomUUID(),
        characteristics = listOf(
            GattCharacteristicInfo(
                uuid = UUID.randomUUID(),
                canNotify = true,
                canIndicate = false,
                canRead = false,
                canWrite = true,
                canWriteNoResponse = false,
                hasClientConfigDescriptor = false,
            )
        )
    )

    assertNull(GattProfileSelector.select(listOf(service), preferred = null))
}
```

- [ ] **Step 2: Implement selector scoring**

Selection rules:

1. If `preferred` UUIDs are present and still valid, return them.
2. Prefer one characteristic that supports notify or indicate, has CCCD, and supports write or write-no-response.
3. Otherwise, choose a notify/indicate characteristic with CCCD and a writable characteristic from the same service.
4. If multiple services tie, choose the one with the fewest characteristics, then stable UUID string order.
5. Return `null` when no safe profile exists; do not guess.

- [ ] **Step 3: Write and implement DataStore test**

```kotlin
class DeviceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndRestoresTheLastConnectionSnapshot() = runTest {
        val file = temporaryFolder.newFile("device.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = this) { file }
        val store = DeviceStore(dataStore)
        val snapshot = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KMF",
            serviceUuid = "33333333-3333-3333-3333-333333333333",
            notifyUuid = "11111111-1111-1111-1111-111111111111",
            writeUuid = "11111111-1111-1111-1111-111111111111",
        )

        store.save(snapshot)

        assertEquals(snapshot, store.snapshot.first())
    }
}
```

- [ ] **Step 4: Run selector and store tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ble.GattProfileSelectorTest' --tests 'com.juncehome.lifepo4ble.data.DeviceStoreTest'
```

Expected: tests pass.

- [ ] **Step 5: Commit when inside a Git repository**

```bash
git rev-parse --is-inside-work-tree
```

If true:

```bash
git add .
git commit -m "feat: add gatt profile selection"
```

### Task 4: Implement BLE Scanner, Session, and Serialized GATT Queue

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/ScannedDevice.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/BleEvent.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/BleScanner.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/BleSession.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/GattOperationQueue.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ble/BleRepository.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/ble/GattOperationQueueTest.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/ble/BleRepositoryTest.kt`

**Interfaces:**
- `ScannedDevice(name: String?, address: String, rssi: Int, serviceUuids: List<UUID>)`.
- `BleEvent` covers `Scanning`, `ScanResults`, `Connecting`, `Connected`, `ServicesDiscovered`, `NotificationReceived`, `WriteQueued`, `WriteCompleted`, `Disconnected`, and `Error`.
- `BleScanner.scan(): Flow<List<ScannedDevice>>` starts scanner on collection and stops it in `awaitClose`.
- `BleSession.connect(device: ScannedDevice, preferred: DeviceSnapshot?): Flow<BleEvent>` owns one `BluetoothGatt`.
- `BleSession.write(bytes: ByteArray): Boolean` enqueues a write only after the profile is ready.
- `GattOperationQueue` serializes descriptor and characteristic writes and completes each operation from the matching callback.

- [ ] **Step 1: Write GATT queue behavior test**

Use a fake operation queue transport, not Android framework classes:

```kotlin
@Test
fun runsOnlyOneGattWriteAtATime() = runTest {
    val started = mutableListOf<String>()
    val queue = GattOperationQueue(testScope = this)

    val first = async { queue.enqueue("descriptor") { started += "descriptor" } }
    val second = async { queue.enqueue("characteristic") { started += "characteristic" } }

    advanceUntilIdle()
    assertEquals(listOf("descriptor"), started)

    queue.completeCurrent(success = true)
    advanceUntilIdle()
    assertEquals(listOf("descriptor", "characteristic"), started)

    queue.completeCurrent(success = true)
    assertTrue(first.await())
    assertTrue(second.await())
}
```

- [ ] **Step 2: Implement BLE session sequencing**

The session must execute this exact order:

1. `connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)` on API 23+.
2. On `STATE_CONNECTED`, call `discoverServices()`.
3. On `onServicesDiscovered(GATT_SUCCESS)`, convert services to `GattServiceInfo`.
4. Select `GattProfile` with `GattProfileSelector`.
5. Call `setCharacteristicNotification(notifyCharacteristic, true)`.
6. Write CCCD `ENABLE_NOTIFICATION_VALUE` or `ENABLE_INDICATION_VALUE` through `GattOperationQueue`.
7. Emit `ServicesDiscovered(profile)` only after CCCD write succeeds.
8. Start a foreground coroutine that writes ASCII bytes `:C\n` every 30 seconds through the same operation queue.
9. On notification callbacks, emit `NotificationReceived(uuid, bytes)`.
10. On disconnect or flow cancellation, cancel polling, disconnect, close the GATT, clear queue, and emit `Disconnected`.

Use API 33+ overloads for characteristic and descriptor writes when available. Do not mutate characteristic values globally on API 33+.

- [ ] **Step 3: Implement scanner**

`BleScanner.scan()` must:

- fail fast with `BleEvent.Error` or repository error state when runtime permission is missing
- include devices with KMF-like names such as `KMF`, `BTG`, or `CH`
- still allow showing all devices through the UI because the meter naming is not guaranteed
- stop scanning when the flow collector is cancelled

- [ ] **Step 4: Implement repository tests with fakes**

```kotlin
@Test
fun repositoryStopsScanBeforeConnecting() = runTest {
    val scanner = FakeBleScanner()
    val session = FakeBleSession()
    val repository = BleRepository(scanner, session)
    val device = ScannedDevice(name = "KMF", address = "AA:BB:CC:DD:EE:FF", rssi = -60, serviceUuids = emptyList())

    repository.startScan()
    repository.connect(device, preferred = null)

    assertTrue(scanner.stopCalled)
    assertEquals(device, session.connectedDevice)
}
```

- [ ] **Step 5: Run BLE unit tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ble.*'
```

Expected: tests pass.

- [ ] **Step 6: Manual BLE smoke test**

Install on one API 30 device or emulator with BLE support and one API 31+ physical Android device:

- grant permissions
- confirm Bluetooth-off UI state appears when adapter is disabled
- scan and find the KMF meter
- connect
- verify selected service/notify/write UUIDs are visible
- verify notifications arrive
- verify `:C\n` is logged outbound only after notification setup succeeds

Record observations in `docs/meter-protocol-notes.md` with the real MAC address redacted.

- [ ] **Step 7: Commit when inside a Git repository**

```bash
git rev-parse --is-inside-work-tree
```

If true:

```bash
git add .
git commit -m "feat: add ble transport"
```

### Task 5: Build ViewModel, Reducer, and Compose Utility Screen

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/ConnectionState.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleUiState.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleStateReducer.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleViewModel.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleScreen.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/components/DeviceList.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/components/StatusPanel.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/components/ReadingPanel.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/ui/components/PacketLogList.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/ui/BleStateReducerTest.kt`
- Create: `app/src/test/java/com/juncehome/lifepo4ble/ui/BleViewModelTest.kt`

**Interfaces:**
- `BleUiState` holds permissions, Bluetooth readiness, scanning state, devices, selected device, connection state, selected UUIDs, `latestReading: KmfReading = KmfReading()`, bounded packet log, and latest error.
- `BleStateReducer.reduce(state, event, parser, nowMs)` maps BLE events into UI state.
- `BleViewModel` exposes `StateFlow<BleUiState>` and functions `startScan()`, `stopScan()`, `connect(device)`, `disconnect()`, and `clearLog()`.

- [ ] **Step 1: Write reducer test**

```kotlin
@Test
fun notificationUpdatesLogAndReading() {
    val parser = KmfLineParser()
    val dataUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val profile = GattProfile(
        serviceUuid = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        notifyUuid = dataUuid,
        writeUuid = dataUuid,
        usesIndications = false,
    )
    val withProfile = BleStateReducer.reduce(
        BleUiState(),
        BleEvent.ServicesDiscovered(profile),
        parser,
        nowMs = 1L,
    )

    val afterPacket = BleStateReducer.reduce(
        withProfile,
        BleEvent.NotificationReceived(dataUuid, ":A=1234,2500,1,120,2400,1000\n".encodeToByteArray()),
        parser,
        nowMs = 2L,
    )

    assertEquals(profile.serviceUuid.toString(), afterPacket.serviceUuid)
    assertEquals(1, afterPacket.packetLog.size)
    assertEquals(12.34, afterPacket.latestReading.voltageV, 0.001)
}
```

- [ ] **Step 2: Write ViewModel test**

```kotlin
@Test
fun connectSavesSuccessfulProfileAndKeepsLogBounded() = runTest {
    val repo = FakeBleRepository()
    val store = FakeDeviceStore()
    val viewModel = BleViewModel(repo, store, clock = { 10L })
    val device = ScannedDevice(name = "KMF", address = "AA:BB:CC:DD:EE:FF", rssi = -55, serviceUuids = emptyList())
    val dataUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

    viewModel.connect(device)
    repo.events.emit(BleEvent.Connected(device))
    repo.events.emit(
        BleEvent.ServicesDiscovered(
            GattProfile(UUID.randomUUID(), dataUuid, dataUuid, usesIndications = false)
        )
    )
    repeat(250) {
        repo.events.emit(BleEvent.NotificationReceived(dataUuid, ":C=12,34\n".encodeToByteArray()))
    }
    advanceUntilIdle()

    assertEquals(200, viewModel.uiState.value.packetLog.size)
    assertEquals(device.address, store.saved?.address)
}
```

- [ ] **Step 3: Implement UI**

The first screen must be the working utility app, not a marketing screen. It must show:

- permission and Bluetooth status
- scan button and stop scan button
- list of discovered devices with name, address, RSSI
- connection state
- selected service, notify, and write UUIDs
- latest parsed KMF readings
- live packet log
- clear-log action
- non-fatal error text

Do not add a raw arbitrary write box in v1. The only automatic write in v1 is the KMF totals poll `:C\n`.

- [ ] **Step 4: Run UI unit tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ui.*'
```

Expected: tests pass.

- [ ] **Step 5: Manual UI smoke test**

On a physical Android device:

- deny permissions and confirm the UI does not crash
- grant permissions and scan
- connect to the KMF meter
- confirm `A=` values update live without manual polling
- wait at least 35 seconds and confirm one outbound `:C\n` log entry and a parsed `C=` total if the meter responds
- rotate the device and confirm the ViewModel state survives

- [ ] **Step 6: Commit when inside a Git repository**

```bash
git rev-parse --is-inside-work-tree
```

If true:

```bash
git add .
git commit -m "feat: add ble utility screen"
```

### Task 6: Final Device Validation and Rollout Notes

**Files:**
- Create: `docs/meter-protocol-notes.md`
- Modify: `README.md`

**Interfaces:**
- `meter-protocol-notes.md` records observed services, selected UUIDs, notification examples, poll examples, phone model, Android version, and whether `neverForLocation` discovered the meter.
- `README.md` explains required Android version, permissions, BLE-only scope, and how to run tests.

- [ ] **Step 1: Create protocol notes template**

Use this exact structure:

````markdown
# KMF BLE Meter Notes

## Device

- Meter label:
- Advertised name:
- MAC address: redacted
- Android device:
- Android version:

## Discovery

- `neverForLocation` scan found meter: yes/no
- Selected service UUID:
- Selected notify UUID:
- Selected write UUID:
- Notify and write same characteristic: yes/no

## Notifications

```text
redacted sample A line:
redacted sample C line:
```

## Polling

- Outbound poll bytes: `3A 43 0A`
- Poll interval observed:
- First response latency:

## Failures

- Permission issues:
- Disconnect behavior:
- Write failures:
````

- [ ] **Step 2: Run full local verification**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: build and unit tests pass.

- [ ] **Step 3: Run physical meter validation**

Use a real KMF meter:

- install debug APK
- grant permissions
- scan with `neverForLocation`
- connect
- verify CCCD subscription succeeds
- verify `A=` notification parsing
- verify `:C\n` outbound poll after notification setup
- verify `C=` total parsing
- disconnect and reconnect
- force-close and reopen app to verify restored device/UUID display

- [ ] **Step 4: Decide permission fallback**

If `neverForLocation` scan fails to discover the meter on the physical test device:

- remove `android:usesPermissionFlags="neverForLocation"` from `BLUETOOTH_SCAN`
- add location disclosure copy in the permission screen
- update `BlePermissionPolicy` if Android policy requires location permission for the chosen scan behavior
- rerun Task 1 permission tests and Task 6 physical validation

If scan succeeds, keep the manifest unchanged.

- [ ] **Step 5: Commit when inside a Git repository**

```bash
git rev-parse --is-inside-work-tree
```

If true:

```bash
git add .
git commit -m "docs: document kmf ble validation"
```

## Final Check

- The app does not hardcode KMF MAC, service UUID, or characteristic UUID.
- `kmf.yml` behavior is represented: notify, line buffering, `A=` parsing, `C=` parsing, 200-byte discard, and `:C\n` polling.
- Android permission behavior is API-specific and test-covered.
- GATT writes are serialized and start only after CCCD subscription succeeds.
- Scanner, session, poll job, and GATT are cleaned up on disconnect and flow cancellation.
- Packet logs are bounded and visible.
- Real-device validation has been recorded before any production rollout.
