# Autonomous Background Battery Collection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After one-time meter association, collect KMF readings through ordinary task removal, process death, and reboot on Android 12+.

**Architecture:** A pure `CollectionController` owns BLE, KMF startup, persistence, retry, and cadence. Android companion/presence, boot, service, and notification components translate system events into controller commands; the UI configures and observes them without owning a GATT session.

**Tech Stack:** Kotlin, Coroutines/Flow, BLE GATT, Companion Device Manager, foreground services, DataStore, Room, Compose, JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-02-background-battery-collection-design.md`

## Global Constraints

- API 31+ plus `FEATURE_COMPANION_DEVICE_SETUP` is required for the autonomous presence-wake guarantee.
- Initial setup grants BLE/notification permissions, selects one meter, and approves the OS companion association sheet.
- Use `connectedDevice` foreground service and an ongoing notification with a Stop action; no location, network, exact alarm, boot scan, battery exemption, or force-stop workaround.
- Keep the KMF notification → MTU 100 → repeated `:*` to A+B flow. Poll `:C\n` every 30 seconds when visible and every 5 minutes in background.
- Settings force-stop and Android Active Apps Stop are unsupported; ordinary Recents task removal is supported.
- Complete the required build/install/real-device log pass. Do not claim reboot/presence success without on-device evidence.

---

### Task 1: Persist autonomous configuration

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/CollectionConfiguration.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/data/DeviceStore.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/data/DeviceStoreTest.kt`

**Interfaces:**

```kotlin
data class CollectionConfiguration(
    val snapshot: DeviceSnapshot,
    val associationId: Int,
    val autonomousEnabled: Boolean,
)

interface DeviceSnapshotStore {
    val snapshot: Flow<DeviceSnapshot?>
    val collectionConfiguration: Flow<CollectionConfiguration?>
    suspend fun enableAutonomousCollection(snapshot: DeviceSnapshot, associationId: Int)
    suspend fun disableAutonomousCollection()
}
```

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun enablesAndRestoresConfiguration() = runTest {
    store.enableAutonomousCollection(snapshot, 42)
    assertEquals(CollectionConfiguration(snapshot, 42, true), store.collectionConfiguration.first())
}

@Test fun disableKeepsMeterButRemovesAutonomousConfiguration() = runTest {
    store.enableAutonomousCollection(snapshot, 42)
    store.disableAutonomousCollection()
    assertEquals(snapshot, store.snapshot.first())
    assertNull(store.collectionConfiguration.first())
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.data.DeviceStoreTest'`

Expected: compile failure because the new configuration API does not exist.

- [ ] **Step 3: Implement the minimal atomic DataStore edit**

Add integer `companion_association_id` and boolean `autonomous_collection_enabled`. Persist snapshot and configuration together; disable removes only association/enabled fields and `clear()` removes all fields.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.data.DeviceStoreTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/juncehome/lifepo4ble/collection/CollectionConfiguration.kt app/src/main/java/com/juncehome/lifepo4ble/data/DeviceStore.kt app/src/test/java/com/juncehome/lifepo4ble/data/DeviceStoreTest.kt
git commit -m "feat: persist autonomous collection configuration"
```

### Task 2: Extract the pure collection controller

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/CollectionController.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/collection/CollectionControllerTest.kt`

**Interfaces:**

```kotlin
enum class CollectionVisibility { FOREGROUND, BACKGROUND }
enum class CollectionPhase { STOPPED, WAITING_FOR_DEVICE, CONNECTING, BOOTSTRAPPING, COLLECTING, ERROR }

data class CollectionState(
    val phase: CollectionPhase,
    val visibility: CollectionVisibility,
    val deviceAddress: String? = null,
    val nextReconnectDelayMs: Long? = null,
    val error: String? = null,
)

interface CollectionController {
    val state: StateFlow<CollectionState>
    fun start(configuration: CollectionConfiguration)
    fun setVisibility(visibility: CollectionVisibility)
    fun onDeviceAppeared(address: String)
    fun onDeviceDisappeared(address: String)
    fun stopByUser()
    fun close()
}
```

- [ ] **Step 1: Write failing cadence/stop tests**

```kotlin
@Test fun backgroundReadyConnectionPollsEveryFiveMinutes() = runTest {
    controller.start(configuration); emitReadyAAndBFrames()
    controller.setVisibility(CollectionVisibility.BACKGROUND)
    advanceTimeBy(299_999); assertEquals(0, fakeRepository.countWrites(":C\\n"))
    advanceTimeBy(1); assertEquals(1, fakeRepository.countWrites(":C\\n"))
}

@Test fun userStopPreventsReconnect() = runTest {
    controller.start(configuration); controller.stopByUser()
    fakeRepository.emit(BleEvent.Disconnected("manual")); advanceUntilIdle()
    assertEquals(1, fakeRepository.connectCalls.size)
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.collection.CollectionControllerTest'`

Expected: compile failure because `CollectionController` does not exist.

- [ ] **Step 3: Implement minimal controller behavior**

Move from `BleViewModel` only parser/history persistence, MTU/bootstrap, polling, and reconnect rules. Inject scheduler/clock for virtual-time tests. Preserve MTU 100, `:*` every 2 seconds until A+B, 30-second foreground and 300-second background polling. Use reconnect delay sequence 1s, 2s, 5s, 15s, 30s, 60s; reset it after READY. Device disappearance disconnects and transitions to `WAITING_FOR_DEVICE` without an app-owned scan.

- [ ] **Step 4: Add and pass readiness/retry tests**

Cover bootstrap stopping after A+B, visible 30-second cadence, rejecting appearance for any address other than the configured meter, retry reset after READY, and history persistence.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/juncehome/lifepo4ble/collection/CollectionController.kt app/src/test/java/com/juncehome/lifepo4ble/collection/CollectionControllerTest.kt
git commit -m "feat: add background collection controller"
```

### Task 3: Add companion association and presence registration

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/CompanionAssociationGateway.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/collection/CompanionAssociationPolicyTest.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt`

**Interfaces:**

```kotlin
interface CompanionAssociationGateway {
    fun isSupported(): Boolean
    fun requestAssociation(address: String, onPending: (IntentSender) -> Unit, onFailure: (Throwable) -> Unit)
    fun associationIdFromResult(resultData: Intent): Int?
    fun startPresenceObservation(address: String)
    fun stopPresenceObservation(address: String)
}

object CompanionAssociationPolicy {
    fun canConfigure(apiLevel: Int, hasFeature: Boolean): Boolean = apiLevel >= 31 && hasFeature
}
```

- [ ] **Step 1: Write failing support-policy tests**

```kotlin
@Test fun api31FeatureDeviceCanConfigure() = assertTrue(CompanionAssociationPolicy.canConfigure(31, true))
@Test fun api30DoesNotClaimAutonomousWake() = assertFalse(CompanionAssociationPolicy.canConfigure(30, true))
@Test fun missingFeatureDoesNotOfferSetup() = assertFalse(CompanionAssociationPolicy.canConfigure(35, false))
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.collection.CompanionAssociationPolicyTest'`

Expected: compile failure because the policy is absent.

- [ ] **Step 3: Implement the Android gateway**

Use an address-filtered `AssociationRequest`, pass its `IntentSender` to the activity, extract the association ID only from a successful result, then begin observation. Use API-31 address observation through API 35 and the request overload on API 36+. Surface `DeviceNotAssociatedException` as re-setup required; never fall back to a periodic raw scan.

- [ ] **Step 4: Verify GREEN and commit**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.collection.CompanionAssociationPolicyTest'`

Expected: PASS.

```bash
git add app/src/main/java/com/juncehome/lifepo4ble/collection/CompanionAssociationGateway.kt app/src/test/java/com/juncehome/lifepo4ble/collection/CompanionAssociationPolicyTest.kt app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt
git commit -m "feat: add meter companion association"
```

### Task 4: Implement foreground service and notification actions

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/CollectionNotification.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/BatteryCollectionService.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/collection/CollectionServiceCommandTest.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt`

**Interfaces:**

```kotlin
object CollectionServiceAction {
    const val START = "com.juncehome.lifepo4ble.collection.START"
    const val STOP = "com.juncehome.lifepo4ble.collection.STOP"
    const val DEVICE_APPEARED = "com.juncehome.lifepo4ble.collection.DEVICE_APPEARED"
    const val DEVICE_DISAPPEARED = "com.juncehome.lifepo4ble.collection.DEVICE_DISAPPEARED"
    const val VISIBILITY_CHANGED = "com.juncehome.lifepo4ble.collection.VISIBILITY_CHANGED"
}
```

- [ ] **Step 1: Write failing command-routing tests**

```kotlin
@Test fun stopRoutesToControllerUserStop() { router.route(Intent(CollectionServiceAction.STOP)); assertEquals(1, fakeController.userStops) }
@Test fun appearedAddressRoutesToController() { router.route(Intent(CollectionServiceAction.DEVICE_APPEARED).putExtra(EXTRA_ADDRESS, ADDRESS)); assertEquals(listOf(ADDRESS), fakeController.appearedAddresses) }
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.collection.CollectionServiceCommandTest'`

Expected: compile failure because service command router does not exist.

- [ ] **Step 3: Implement service lifecycle**

Create the notification channel and call `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` immediately. Route explicit actions, return `START_STICKY` only with autonomous configuration, remove notification and stop self after user Stop, and log-only in `onTaskRemoved()`. Notification must have app-tap and immutable one-shot Stop pending intents.

- [ ] **Step 4: Verify GREEN and commit**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.collection.CollectionServiceCommandTest'`

Expected: PASS.

```bash
git add app/src/main/java/com/juncehome/lifepo4ble/collection/CollectionNotification.kt app/src/main/java/com/juncehome/lifepo4ble/collection/BatteryCollectionService.kt app/src/test/java/com/juncehome/lifepo4ble/collection/CollectionServiceCommandTest.kt app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt
git commit -m "feat: run collection as foreground service"
```

### Task 5: Bridge companion callbacks and reboot restoration

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/KmfCompanionDeviceService.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/BootCompletedReceiver.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/collection/AutonomousRestorePolicy.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/collection/AutonomousRestorePolicyTest.kt`

- [ ] **Step 1: Write failing restore tests**

```kotlin
@Test fun enabledApi31ConfigurationRestoresPresence() = assertTrue(shouldRestore(configuration, 31))
@Test fun disabledConfigurationDoesNotRestorePresence() = assertFalse(shouldRestore(configuration.copy(autonomousEnabled = false), 35))
@Test fun api30DoesNotClaimWake() = assertFalse(shouldRestore(configuration, 30))
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.collection.AutonomousRestorePolicyTest'`

Expected: compile failure because `AutonomousRestorePolicy` is absent.

- [ ] **Step 3: Implement callbacks and receiver**

`KmfCompanionDeviceService` sends explicit appeared/disappeared intents through `Context.startForegroundService` on API 26+ (and `startService` below it). The boot/package-replaced receiver reads DataStore asynchronously, restores presence only when `configuration.autonomousEnabled && apiLevel >= 31`, and logs each decision. Neither component starts a raw scan or direct GATT operation.

- [ ] **Step 4: Verify GREEN and commit**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.collection.AutonomousRestorePolicyTest'`

Expected: PASS.

```bash
git add app/src/main/java/com/juncehome/lifepo4ble/collection/KmfCompanionDeviceService.kt app/src/main/java/com/juncehome/lifepo4ble/collection/BootCompletedReceiver.kt app/src/main/java/com/juncehome/lifepo4ble/collection/AutonomousRestorePolicy.kt app/src/test/java/com/juncehome/lifepo4ble/collection/AutonomousRestorePolicyTest.kt
git commit -m "feat: restore collection from companion presence"
```

### Task 6: Declare required Android contract

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/platform/BlePermissionPolicy.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/platform/BlePermissionPolicyTest.kt`

- [ ] **Step 1: Write failing notification permission test**

```kotlin
@Test fun api33IncludesNotificationPermission() {
    assertTrue(BlePermissionPolicy.requiredRuntimePermissions(33).contains(Manifest.permission.POST_NOTIFICATIONS))
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.platform.BlePermissionPolicyTest'`

Expected: FAIL because API 33 currently returns only BLE permissions.

- [ ] **Step 3: Add manifest and runtime declarations**

Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, and `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`. Declare non-exported collector/receiver and exported companion service bound only by `BIND_COMPANION_DEVICE_SERVICE`. Add notification permission only on API 33+.

- [ ] **Step 4: Verify GREEN and manifest build**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.platform.BlePermissionPolicyTest' :app:assembleDebug`

Expected: tests PASS and APK assembles without a foreground-service manifest error.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/juncehome/lifepo4ble/platform/BlePermissionPolicy.kt app/src/test/java/com/juncehome/lifepo4ble/platform/BlePermissionPolicyTest.kt
git commit -m "feat: declare autonomous collection permissions"
```

### Task 7: Wire initial setup, status, and stop UI

**Files:**
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/MainActivity.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleViewModel.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleUiState.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/DashboardScreen.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/ui/BleViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

```kotlin
@Test fun associationSuccessPersistsAndStartsService() = runTest {
    viewModel.onAssociationCompleted(42); advanceUntilIdle()
    assertEquals(42, store.collectionConfiguration.first()?.associationId)
    assertEquals(listOf(CollectionServiceAction.START), serviceStarter.actions)
}

@Test fun stopDisablesBeforeStoppingService() = runTest {
    viewModel.stopAutonomousCollection(); advanceUntilIdle()
    assertNull(store.collectionConfiguration.first())
    assertEquals(listOf(CollectionServiceAction.STOP), serviceStarter.actions)
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ui.BleViewModelTest'`

Expected: compile failure because association/service command behavior is absent.

- [ ] **Step 3: Implement narrow UI flow**

After profile discovery, offer `Enable autonomous collection`; launch CDM `IntentSender` from `MainActivity`, persist only a completed association, register observation, and start service. Send visibility command in `onStart`/`onResume` and `onStop`. Remove ViewModel-owned reconnect/poll loops. Add Dashboard setup/status/Stop controls; retain scanner only to choose the meter initially.

- [ ] **Step 4: Verify GREEN and whole JVM suite**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ui.BleViewModelTest' :app:testDebugUnitTest`

Expected: PASS; all protocol/history/reducer tests remain green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/juncehome/lifepo4ble/MainActivity.kt app/src/main/java/com/juncehome/lifepo4ble/ui/BleViewModel.kt app/src/main/java/com/juncehome/lifepo4ble/ui/BleUiState.kt app/src/main/java/com/juncehome/lifepo4ble/ui/DashboardScreen.kt app/src/test/java/com/juncehome/lifepo4ble/ui/BleViewModelTest.kt
git commit -m "feat: configure autonomous collection from ui"
```

### Task 8: Validate on the physical meter and document results

**Files:**
- Modify: `docs/meter-protocol-notes.md`
- Modify: `README.md`

- [ ] **Step 1: Document the exact support boundary**

Record one-time association, API 31+ requirement, stable-address/bonded-resolvable-address condition, supported Recents swipe, and unsupported Active Apps/Settings stop. Do not record the real meter address.

- [ ] **Step 2: Run full automated verification**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: all JVM tests PASS and debug APK exists.

- [ ] **Step 3: Run required install-launch-log verification**

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.juncehome.lifepo4ble/.MainActivity
adb shell pidof -s com.juncehome.lifepo4ble
```

Expected: one authorized unlocked device and no launch crash.

- [ ] **Step 4: Validate normal background behavior**

Grant permissions, associate meter, enable collection, close the official KMF app, wait for A/B readiness, then swipe this app from Recents. Verify the ongoing notification and new sample timestamps after reopening. Inspect `adb logcat -d -s KMF-BLE`, `adb shell run-as com.juncehome.lifepo4ble cat files/kmf-ble.log`, and a real exported CSV.

- [ ] **Step 5: Validate appearance, reboot, and Stop**

Take meter out of range and return it; verify companion appearance/disappearance logs and resumed samples. Reboot, leave activity unopened, return meter to range, then verify boot/companion logs and a new persisted sample. Use notification Stop and prove no reconnect occurs. Label Active Apps/Settings force-stop as unsupported, not failed validation.

- [ ] **Step 6: Commit evidence**

```bash
git add docs/meter-protocol-notes.md README.md
git commit -m "docs: record autonomous collection validation"
```
