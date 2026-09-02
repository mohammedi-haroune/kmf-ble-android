# Background battery collection design

## Goal

Collect and persist KMF battery readings while the activity is not visible, including after the user switches apps or removes the app task from Recents, while keeping phone and meter use modest.

## Scope and boundaries

- The user starts collection from the foreground app after granting Bluetooth permissions. The collector stays active while the task is backgrounded or swiped away.
- The app does not attempt to restart after a Settings force-stop or the Android Active Apps **Stop** control. Android terminates the process in those cases.
- The collector has an ongoing, user-visible foreground-service notification with a stop action. It is not a silent background service.
- Preserve one selected KMF meter only. The existing remembered `DeviceSnapshot` remains the source of its address and discovered GATT profile.
- Do not introduce network sync, location collection, raw frame history, battery-optimization exemptions, boot restart, or companion-device association in this change.

## Chosen approach

Use a direct Android foreground service declared with the `connectedDevice` type. The service owns the only `BleRepository`, parser, readiness/reconnect loop, KMF startup traffic, and battery-history writes. The Compose activity becomes a client: it can start or stop collection and renders its state/history without owning the GATT connection.

This is preferable to periodic WorkManager because periodic work is inexact and cannot run more frequently than every 15 minutes. It is preferable to maintaining the current activity-owned connection because that connection ends with the activity/process. A `CompanionDeviceService` is intentionally deferred: it needs device-association UX and should be considered only if real-device testing shows that foreground-service battery cost is unacceptable.

## Data and connection flow

1. The user connects to a meter in the app. On successful service discovery, the app saves its `DeviceSnapshot` as it does today.
2. The user enables collection. While the activity is foregrounded, it starts `BatteryCollectionService`; the service calls `startForeground()` immediately with the `connectedDevice` foreground-service type and an ongoing notification.
3. The service restores the remembered snapshot, connects directly to the address, enables notifications, requests MTU 100, and retries `:*` every two seconds until both A and B frames are observed.
4. Every notification is parsed, merged, and persisted through the existing `KmfHistoryStore`. Only battery samples are retained, as today.
5. Once startup is ready, the service sends `:C\n` every 30 seconds while the app is visible and every five minutes while it is backgrounded. Visibility is sent explicitly by the activity lifecycle, not inferred from process lifetime.
6. An unexpected GATT disconnect reconnects with bounded exponential backoff; a user Stop action suppresses reconnects, disconnects GATT, removes the notification, and stops the service.
7. Removing the activity task does not invoke that Stop action and does not stop the service. `onTaskRemoved()` logs the event only. A process kill uses `START_STICKY` for ordinary system recovery, then rehydrates the saved snapshot; force-stop/Active Apps stop remains deliberately unsupported.

## Components

### `collection/CollectionController`

A testable, Android-free state machine that receives BLE events, owns the protocol parser and background jobs, and exposes a collection state flow. It handles persisted samples, bootstrap retries, foreground/background polling intervals, reconnect backoff, explicit user stops, and logging. It consumes `BleRepositoryContract`, `DeviceSnapshotStore`, and `KmfHistoryStore`.

### `collection/BatteryCollectionService`

An Android `Service` that creates the controller from `BleApplication.graph`, promotes itself to the foreground immediately, bridges notification actions and activity visibility commands to the controller, and builds the status notification. It neither contains parsing rules nor writes database records directly.

### `collection/CollectionNotification`

Creates the notification channel, notification content, and immutable pending intents. The notification shows collecting, connecting, paused/error, or stopped status and provides a Stop action. It launches the existing `MainActivity` when tapped.

### App graph and UI

`AppGraph` exposes singleton BLE/history dependencies and a collection-controller factory so the service is the only owner of the BLE session. `BleViewModel` becomes an observer/command façade; it must not create a second repository or background polling loop. Screens show collection status and provide an explicit start/stop control.

## Android configuration and permissions

- Declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest.
- Declare `BatteryCollectionService` as non-exported, `android:foregroundServiceType="connectedDevice"`, and `android:stopWithTask="false"`.
- Retain the existing runtime `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permission flow. They satisfy the connected-device foreground-service prerequisite on supported Android versions.
- Add `POST_NOTIFICATIONS` to the runtime policy for Android 13+, request it before enabling collection, and show an actionable explanation if denied. The service cannot promise a visible, controllable ongoing notification without it.
- Do not request location, unrestricted battery, exact alarm, boot-completed, companion-device, or network permissions.

## Reliability and resource policy

- Background cadence: five minutes after A/B readiness. This is one-tenth of the present 30-second polling rate and retains a warm BLE connection, avoiding repeated discovery/scanning.
- Foreground cadence: preserve the current 30-second totals poll for live use.
- Reconnect delays use a capped exponential sequence (1, 2, 5, 15, 30, then 60 seconds) and reset after a stable ready connection. No scanning occurs while reconnecting to the remembered address.
- Log every service start/stop, task removal, permission refusal, foreground/background cadence transition, reconnect attempt, GATT event, sample persistence failure, and notification-action result under `KMF-BLE`.
- Stop safely if Bluetooth permission is revoked or Bluetooth is disabled; keep the notification in an actionable error state rather than retrying in a tight loop.

## Test and verification requirements

- JVM tests cover background cadence selection, bootstrap readiness, reconnect backoff/reset, explicit stop suppressing reconnect, notification-action command mapping, and service task-removal policy.
- Existing protocol/parser/history tests remain green.
- On a connected, unlocked phone: grant BLE and notification permissions; connect the meter; enable collection; swipe the app task away; verify the foreground notification remains, then verify new timestamped battery samples appear after relaunch without the official KMF app open.
- Validate the five-minute interval from `KMF-BLE` logs and exported CSV timestamps; verify Stop ends GATT/reconnects and removes the notification.
- Separately test Bluetooth-off and out-of-range recovery. The test report must distinguish a normal Recents swipe from an unsupported Active Apps/Settings stop.

## Acceptance criteria

1. After collection is explicitly enabled, data persists while the app is backgrounded or removed from Recents.
2. The only background `:C\n` polling interval is five minutes after KMF startup readiness; the visible-app interval remains 30 seconds.
3. The service never creates a duplicate BLE session when the UI opens.
4. The notification always offers a reliable way to stop collection.
5. A force-stop or Active Apps stop is documented as outside the guarantee, with no attempted workaround.
