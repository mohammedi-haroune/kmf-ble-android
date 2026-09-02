# Background battery collection design

## Goal

Collect and persist KMF battery readings while the activity is not visible, including after the user switches apps or removes the app task from Recents, while keeping phone and meter use modest.

## Scope and boundaries

- A mandatory one-time setup grants Bluetooth and notification permissions, selects the meter, and approves Android's companion-device association UI. Once enabled, collection resumes without reopening the activity after ordinary process death, task removal, or reboot.
- The app does not attempt to restart after a Settings force-stop or the Android Active Apps **Stop** control. Android puts the package in its stopped state in those cases and blocks autonomous recovery until the user directly or indirectly starts it again.
- The collector has an ongoing, user-visible foreground-service notification with a stop action. It is not a silent background service.
- Preserve one selected KMF meter only. The existing remembered `DeviceSnapshot` remains the source of its address and discovered GATT profile.
- Do not introduce network sync, location collection, raw frame history, unrestricted battery optimization, exact alarms, boot-time scanning, or any workaround for a force-stopped package.

## Chosen approach

Use Android's Companion Device Manager (CDM) plus a `connectedDevice` foreground service. During one-time setup, CDM associates the selected KMF meter and returns an association ID. A `CompanionDeviceService` receives Android-managed device-presence callbacks even when the app process is not alive; on an appearance callback it starts the collector under CDM's foreground-service-start exemption. The collector owns the only `BleRepository`, parser, readiness/reconnect loop, KMF startup traffic, and battery-history writes. The Compose activity becomes a client: it configures or stops collection and renders its state/history without owning the GATT connection.

This is preferable to periodic WorkManager because periodic work is inexact and cannot run more frequently than every 15 minutes. It is preferable to maintaining the current activity-owned connection because that connection ends with the activity/process. Android specifically recommends `CompanionDeviceService` for long-lived BLE notification listening. The design requires API 31 or higher for Android-managed presence observation; on older supported versions, the app may keep an already-started collector alive but cannot claim the same autonomous wake guarantee.

## Data and connection flow

1. The user connects to a meter, grants runtime permissions, and completes the system companion-device association sheet. The app persists the discovered `DeviceSnapshot`, association ID, and an explicit autonomous-collection-enabled flag.
2. The app registers Android-managed presence observation for that associated meter. On API 31+, Android binds `KmfCompanionDeviceService` and sends a presence event when the meter appears, even if this app process was not running.
3. On an appearance callback, `KmfCompanionDeviceService` starts `BatteryCollectionService`. The service calls `startForeground()` immediately with the `connectedDevice` foreground-service type and an ongoing notification, then restores the saved snapshot and connects directly.
4. The service enables notifications, requests MTU 100, and retries `:*` every two seconds until both A and B frames are observed. Every notification is parsed, merged, and persisted through the existing `KmfHistoryStore`; only battery samples are retained.
5. Once startup is ready, the service sends `:C\n` every 30 seconds while the activity is visible and every five minutes while it is backgrounded. Visibility is sent explicitly by the activity lifecycle, not inferred from process lifetime.
6. An unexpected GATT disconnect reconnects with bounded exponential backoff. A device-disappeared callback stops the connection and leaves Android responsible for waking the app when the meter reappears.
7. `BootCompletedReceiver` runs only when autonomous collection is enabled. It restores the presence observation and stays dormant until Android reports that the associated meter appears, at which point the companion service starts the collector. Its registration and post-reboot behavior must be verified on the real phone because the reference API does not document whether an existing observation survives every reboot/OEM implementation.
8. Removing the activity task does not invoke the Stop action. `onTaskRemoved()` logs the event only. `START_STICKY` provides ordinary process-kill recovery. Force-stop/Active Apps stop remains deliberately unsupported.

## Components

### `collection/CollectionController`

A testable, Android-free state machine that receives BLE events, owns the protocol parser and background jobs, and exposes a collection state flow. It handles persisted samples, bootstrap retries, foreground/background polling intervals, reconnect backoff, explicit user stops, and logging. It consumes `BleRepositoryContract`, `DeviceSnapshotStore`, and `KmfHistoryStore`.

### `collection/KmfCompanionDeviceService`

An Android `CompanionDeviceService` that receives system presence events for the associated meter. It contains no GATT or parser logic: it starts the collection service only on a device-appeared event, and signals it to disconnect on disappearance. This is the process-wake boundary.

### `collection/BootCompletedReceiver`

A non-exported broadcast receiver for `BOOT_COMPLETED` and package-replacement restoration. It rehydrates only the saved autonomous-collection configuration and delegates presence handling; it never performs its own BLE scan or endless retry loop.

### `collection/BatteryCollectionService`

An Android `Service` that creates the controller from `BleApplication.graph`, promotes itself to the foreground immediately, bridges notification actions and activity visibility commands to the controller, and builds the status notification. It neither contains parsing rules nor writes database records directly.

### `collection/CollectionNotification`

Creates the notification channel, notification content, and immutable pending intents. The notification shows collecting, connecting, paused/error, or stopped status and provides a Stop action. It launches the existing `MainActivity` when tapped.

### App graph and UI

`AppGraph` exposes singleton BLE/history dependencies, a companion-association gateway, and a collection-controller factory so the service is the only owner of the BLE session. `DeviceStore` additionally retains association ID and explicit autonomous-collection enablement. `BleViewModel` becomes an observer/command façade; it must not create a second repository or background polling loop. Screens expose one-time setup, association status, current collector status, and an explicit Stop/disable control.

## Android configuration and permissions

- Declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest.
- Declare `BatteryCollectionService` as non-exported, `android:foregroundServiceType="connectedDevice"`, and `android:stopWithTask="false"`.
- Declare `KmfCompanionDeviceService` with the `android.companion.CompanionDeviceService` intent action, `android.permission.BIND_COMPANION_DEVICE_SERVICE`, and the required exported binding contract. Check for `FEATURE_COMPANION_DEVICE_SETUP` before offering setup.
- Declare `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` and `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`; the latter is the narrowly scoped CDM exemption to start the collector from a presence callback. Do not request `REQUEST_COMPANION_RUN_IN_BACKGROUND` unless real-device evidence shows it is required.
- Declare a non-exported boot receiver for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, plus `RECEIVE_BOOT_COMPLETED`.
- Retain the existing runtime `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permission flow. They satisfy the connected-device foreground-service prerequisite on supported Android versions.
- Add `POST_NOTIFICATIONS` to the runtime policy for Android 13+, request it before enabling collection, and show an actionable explanation if denied. The service cannot promise a visible, controllable ongoing notification without it.
- Do not request location, unrestricted battery, exact alarm, `SYSTEM_ALERT_WINDOW`, network, or any force-stop workaround permission.

## Reliability and resource policy

- Background cadence: five minutes after A/B readiness. This is one-tenth of the present 30-second polling rate and retains a warm BLE connection, avoiding repeated discovery/scanning.
- Foreground cadence: preserve the current 30-second totals poll for live use.
- Reconnect delays use a capped exponential sequence (1, 2, 5, 15, 30, then 60 seconds) and reset after a stable ready connection. No app-owned scanning occurs while reconnecting to the remembered address; Android's companion-presence scan is used only to wake the collector when the associated meter is present.
- Log every service start/stop, task removal, permission refusal, foreground/background cadence transition, reconnect attempt, GATT event, sample persistence failure, and notification-action result under `KMF-BLE`.
- Stop safely if Bluetooth permission is revoked or Bluetooth is disabled; keep the notification in an actionable error state rather than retrying in a tight loop.

## Test and verification requirements

- JVM tests cover association configuration persistence, background cadence selection, bootstrap readiness, reconnect backoff/reset, explicit stop suppressing reconnect, device appearance/disappearance command mapping, boot-restoration decisions, notification-action command mapping, and service task-removal policy.
- Existing protocol/parser/history tests remain green.
- On a connected, unlocked Android 12+ phone: grant BLE and notification permissions; associate the meter through the system UI; enable autonomous collection; swipe the app task away; verify the foreground notification remains, then verify new timestamped battery samples appear after relaunch without the official KMF app open.
- Reboot the phone with autonomous collection enabled, leave the app unopened, bring the associated meter into range, and verify `KmfCompanionDeviceService` / `BootCompletedReceiver` log entries plus a new persisted sample. Repeat after the process is stopped by the system, not by force-stop/Active Apps.
- Validate the five-minute interval from `KMF-BLE` logs and exported CSV timestamps; verify Stop ends GATT/reconnects and removes the notification.
- Separately test Bluetooth-off and out-of-range recovery. The test report must distinguish a normal Recents swipe from an unsupported Active Apps/Settings stop.

## Acceptance criteria

1. After one-time setup and explicit autonomous enablement, data persists while the app is backgrounded or removed from Recents and resumes when Android reports the associated meter after a normal process kill or reboot.
2. The only background `:C\n` polling interval is five minutes after KMF startup readiness; the visible-app interval remains 30 seconds.
3. The service never creates a duplicate BLE session when the UI opens.
4. The notification always offers a reliable way to stop collection.
5. A force-stop or Active Apps stop is documented as outside the guarantee, with no attempted workaround; API 31+ is documented as the floor for the autonomous presence-wake guarantee.
