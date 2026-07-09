# Dashboard, History, and Vico Analytics Plan

> **For agentic workers:** Follow this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Keep changes small, testable, and scoped.

**Goal:** Turn the current KMF BLE utility screen into a dashboard-first Android app that stores all KMF meter observations in SQLite through Room, defaults to dark mode, shows the current battery state first, and adds Vico-based realtime and historical analytics.

## Progress

- Persistence-first slice is complete:
  - Room + KSP dependencies are in place.
  - `KmfDatabase`, frame/sample entities, DAOs, and `KmfHistoryRepository` are wired.
  - Inbound notifications, outbound write completions, and merged battery samples now persist from the existing BLE/ViewModel path.
  - Theme now defaults to dark mode.
- Navigation Compose is in place, Dashboard is the start destination, and the old BLE utility screen now lives under Diagnostics.
- Vico, fuller dashboard cards, history pages, and energy analytics are intentionally deferred.
- `planning/INDEX.md` should treat this file as the active implementation plan.
- Continue with Task 7 next: build the real dashboard cards and empty states.

## Product Direction

- Persist every useful observation gathered from the KMF meter while the app is running and connected.
- Store raw BLE/frame evidence and merged battery-state samples.
- Open on a dark-mode dashboard showing the current battery state.
- Add pages for realtime charts, historical analytics, energy totals, and diagnostics.
- Use Vico for charts.
- Keep the app native Kotlin + Jetpack Compose.
- Do not rewrite to Flutter in this phase.

## Non-Goals

- No Flutter rewrite.
- No background service in this phase.
- No cloud sync.
- No MQTT, Wi-Fi setup, sockets, or remote dashboard.
- No hardcoded KMF MAC address, service UUID, notify UUID, or write UUID.
- No guessed user-facing decoding of unknown KMF fields.

## Architecture

Use:

- Room over SQLite for KMF history.
- DataStore for last device/profile and future preferences.
- Vico for charts.
- Navigation Compose for Dashboard / Live / History / Energy / Diagnostics.
- Existing BLE, protocol, and reducer layers without transport rewrites.

## Data Model

### `kmf_frame_event`

Stores raw evidence.

Columns:

- `id`
- `timestampMs`
- `deviceAddress`
- `deviceName`
- `direction`
- `frameType`
- `rawHex`
- `rawAscii`
- `parsedFieldsCsv`
- `serviceUuid`
- `notifyUuid`
- `writeUuid`
- `writeSuccess`
- `error`

### `kmf_battery_sample`

Stores merged battery snapshots for dashboard and charts.

Columns:

- `id`
- `timestampMs`
- `deviceAddress`
- `deviceName`
- `voltageV`
- `currentA`
- `powerW`
- `charging`
- `minutesRemaining`
- `remainingAh`
- `capacityAh`
- `socPercent`
- `chargeKwh`
- `dischargeKwh`
- `hasAFrame`
- `hasBFrame`
- `hasCFrame`
- `rawAFieldsCsv`
- `rawBFieldsCsv`
- `rawCFieldsCsv`
- `connectionState`
- `serviceUuid`
- `notifyUuid`
- `writeUuid`

## Pages

### Dashboard

Default start page.

Shows:

- SoC hero card
- charging/discharging state
- voltage
- current
- power
- remaining Ah
- capacity Ah
- time remaining
- charge/discharge kWh totals
- connection state
- last update timestamp
- compact recent trend

### Live

Realtime charts:

- Power over time
- Current over time
- Voltage over time
- SoC over time

Ranges:

- 10 minutes
- 30 minutes
- 1 hour

### History

Historical analytics grouped by:

- 5 minutes
- 1 hour
- 2 hours
- 4 hours
- 12 hours
- day
- week
- month

Charts:

- SoC
- voltage
- current
- power

### Energy

Shows:

- total charged kWh
- total discharged kWh
- charged/discharged delta by bucket
- daily/hourly energy flow

### Diagnostics

Keeps debug tools:

- scan/connect/disconnect controls
- selected UUIDs
- bootstrap readiness
- raw A/B/C fields
- packet log
- database row counts
- future export action

## Tasks

### Task 1: Add Dependencies

- [x] Add Room dependencies.
- [x] Add KSP if using Room compiler through KSP.
- [ ] Add Vico Compose and Vico Material3 modules later with chart UI work.
- [x] Add Navigation Compose later with dashboard shell work.
- [x] Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`.

### Task 2: Add Database Schema

- [x] Create `KmfDatabase`.
- [x] Create `KmfFrameEventEntity`.
- [x] Create `KmfBatterySampleEntity`.
- [x] Create DAOs.
- [x] Add indexes on `timestampMs` and `(deviceAddress, timestampMs)`.
- [x] Add insert/latest/recent queries.
- [ ] Add grouped queries later with historical analytics.

### Task 3: Add History Repository

- [x] Create `KmfHistoryRepository`.
- [x] Add raw event insert methods.
- [x] Add battery sample insert methods.
- [x] Add latest/recent flows.
- [ ] Add grouped flows later with historical analytics.
- [x] Wire repository in `AppGraph`.

### Task 4: Persist KMF Observations

- [x] Preserve raw integer fields in parsed A/B/C frame models.
- [x] Insert inbound raw packet events.
- [x] Insert outbound write events.
- [x] Insert merged battery sample snapshots.
- [x] Preserve unknown fields as raw CSV when parsing fails or no full frame is produced.
- [x] Ensure malformed data remains non-fatal.

### Task 5: Default To Dark Mode

- [x] Add `DarkColorScheme`.
- [x] Keep `LightColorScheme`.
- [x] Make `KmfBleTheme(darkTheme: Boolean = true, ...)`.
- [x] Verify readability.

### Task 6: Add Navigation Shell

- [x] Add Dashboard, Live, History, Energy, Diagnostics destinations.
- [x] Make Dashboard the start destination.
- [x] Add Material3 bottom navigation.
- [x] Move current debug screen behavior into Diagnostics.

### Task 7: Build Dashboard

- [ ] Add `DashboardScreen`.
- [ ] Add `BatteryHeroCard`.
- [ ] Add `MetricCard`.
- [ ] Add `EnergyTotalsCard`.
- [ ] Add `ConnectionSummaryCard`.
- [ ] Add empty states for no device/no data/disconnected.

### Task 8: Add Vico Live Charts

- [ ] Add reusable Vico line chart component.
- [ ] Add range selector.
- [ ] Add SoC chart.
- [ ] Add power chart.
- [ ] Add current chart.
- [ ] Add voltage chart.

### Task 9: Add Historical Analytics

- [ ] Add grouped DAO queries.
- [ ] Add range selector.
- [ ] Add bucket mapping.
- [ ] Show SoC, voltage, current, and power history.

### Task 10: Add Energy Analytics

- [ ] Show charged/discharged totals.
- [ ] Add energy delta by bucket.
- [ ] Use bar charts for grouped deltas.
- [ ] Handle meter total resets safely.

### Task 11: Diagnostics

- [ ] Move packet log to Diagnostics.
- [ ] Show latest raw A/B/C fields.
- [ ] Show UUIDs and bootstrap readiness.
- [ ] Show database row counts.
- [ ] Add future export placeholder.

### Task 12: Tests

- [ ] DAO insert/read tests.
- [ ] Latest sample query tests.
- [ ] Grouped aggregation tests.
- [ ] Energy delta tests.
- [ ] Parser raw field preservation tests.
- [ ] ViewModel persistence tests.

### Task 13: Device Validation

- [ ] Launch app and confirm dark dashboard opens first.
- [ ] Connect to meter.
- [ ] Confirm dashboard updates.
- [ ] Confirm `:*` bootstrap still reaches A+B readiness.
- [ ] Confirm `:C\n` polling still works.
- [ ] Confirm database rows are inserted.
- [ ] Confirm Live charts update.
- [ ] Leave app connected for at least 10 minutes.
- [ ] Confirm historical buckets appear.
- [ ] Force-stop/relaunch and confirm history remains.

## Recommended First Slice

1. Add Room dependencies.
2. Add database schema.
3. Persist raw events and battery samples.
4. Default the app to dark mode.
5. Keep the existing UI temporarily.

Only after persistence is proven should Dashboard, Vico charts, and navigation be implemented.
