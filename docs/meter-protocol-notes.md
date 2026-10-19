# KMF BLE Meter Notes

## Frame decoding reference (read before touching protocol, reducer or collection code)

Source of truth: `protocol/KmfLineParser.kt` (bytes to frames) and `protocol/KmfReadingMerger.kt`
(frames to `KmfReading`). `planning/resources/kmf.yml` is the ESPHome behavior reference.
Example values below are illustrative, not real observations.

### Transport

- ASCII lines on one notify characteristic, each `:<letter>=<csv>,` ending in CR LF.
- A line may be split across several notifications, and a notification may hold more than one line.
  `KmfLineParser` buffers by line terminator; never parse a single notification in isolation.
- All fields are integers. Any non-integer field rejects the whole line.

### Frame A (live values), the one with two shapes

Full A (at least 6 fields), sent after `:*` has been accepted:

| idx | meaning | decode |
|-----|---------|--------|
| 0 | voltage | raw / 100 V |
| 1 | current magnitude | abs(raw) / 1000 A |
| 2 | charging flag | 1 = charging, else discharging |
| 3 | minutes remaining | raw minutes |
| 4 | remaining charge | raw / 1000 Ah |
| 5 | capacity setting | raw / 10 Ah |

Signed current = `+` when charging, `-` when discharging. Power = `raw0 * raw1 / 100000`, same sign.
SoC is not a field: `remaining Ah / capacity Ah * 100`, clamped to 0..100.

Short A (4 or 5 fields, e.g. `:A=1331,520,1,2380,1`) is what the meter pushes on its own before
startup completes. It has no remaining Ah and no capacity.

**TRAP: `remainingAh` of a short A frame is not stored charge.** `KmfLineParser` derives it as
`current * minutesRemaining / 60`. Field 3 is the time to the end of the current direction:

- discharging: time to empty, so the derived Ah really is the charge left;
- charging: time to full, so the derived Ah is the charge still missing.

Treating the charging value as remaining Ah gave about 11% SoC on a 180 Ah pack that was at 89%
and flashed a false "Low battery" on every resync. `KmfReadingMerger` therefore does this for short
A frames: charging uses `capacity - derived`, discharging uses `derived`, and with no known capacity
it keeps the previous remaining Ah and reports SoC 0 (the UI ignores low battery while
`capacityAh == 0`). Never copy a short frame's `remainingAh`/`socPercent` straight into the UI.

### Frame B (state, required)

Comma separated integers, only presence is used. Its arrival is part of the startup readiness
condition (see below), so do not drop it as noise. `KmfReadingMerger` leaves the reading unchanged.

### Frame C (energy totals)

Field 0 = charge kWh, field 1 = discharge kWh, both raw / 1000. Remaining fields are unused.
The meter sends C only when asked (`:C` + newline), not on its own.

### Merging rules (`KmfReadingMerger`)

- A updates voltage, current, power, charging, minutes, remaining Ah, status, and capacity/SoC.
- A capacity of 0 in a frame means "unknown", never "zero capacity": keep the last known capacity.
- C updates only the kWh totals. B updates nothing.
- A `KmfReading()` starts as all zeros. A reading built from one short A frame on top of zeros is
  incomplete and must not be shown or saved as a sample.

### Session startup and the "complete session" rule

1. Enable notifications, request MTU 100, then send `:*` and repeat it about every 2 s until both A and B
   were parsed. Only then is the session `COLLECTING`.
2. Send `:C` as soon as A and B exist and repeat about every 2 s until a C frame arrives.
3. A battery sample is saved only after this session saw A, B and C
   (`KmfHistoryRepository.SessionFrameState`). That flag state is per device in memory and is cleared by
   `KmfHistoryStore.resetSession`.
4. `resetSession` must run at the start of every connection. `CollectionControllerImpl` calls it on
   `Connecting` and again on `ServicesDiscovered`.

### Resync display rules

- The UI keeps the last reading while reconnecting to the same meter (`BleStateReducer`, `Connecting`
  branch); it only zeroes when the meter changes. A "Syncing" pill with zero values is a bug.
- The Now tab reads the newest saved sample as well as live frames, so one bad saved sample reaches the UI.

### Known regressions, so they are not repeated

1. Zero values after Start with Background collection off (commit `f5b42a6`, fixed after). The service scope
   moved to `Dispatchers.Main`; `CollectionControllerImpl` collected `repository.events` lazily, missed the
   no-replay `Connecting` event that `start()` emits, skipped `resetSession`, and stale A/B/C flags let a
   zero-based partial reading be saved. Rule: the event collector must subscribe immediately
   (`CoroutineStart.UNDISPATCHED`) and any reset must not depend on a single event. Test controller start
   timing with a dispatcher that delays collector start.
2. False "Low battery" on resync: the short A frame trap above.

Repro for both: Device tab, switch Background collection off; Now tab, press Start. Watch for a zero or
low SoC for the first seconds, then check `adb shell run-as com.juncehome.lifepo4ble cat files/kmf-ble.log`
for `merged reading` lines right after `Now: turn on collection`.

## Autonomous-collection support boundary

- One mandatory setup is required: grant Bluetooth and notification permissions,
  select one meter, and complete Android companion-device association. Approve
  the system association UI if Android shows it.
- The autonomous presence-wake guarantee requires Android API 31+ and
  `FEATURE_COMPANION_DEVICE_SETUP`.
- The selected meter must keep a stable address, or use an address Android can
  resolve after bonding.
- An ordinary Recents swipe is a supported design path: the collection service
  is declared to remain running and reconnect when Android reports the
  companion meter present.
- Android Active Apps **Stop** and Settings force-stop are unsupported. Android
  places the package in a stopped state, so the app cannot restart itself until
  the user starts it again.

## 2026-09-03 device evidence

- An authorized, unlocked Android device accepted the debug APK and launched
  the app successfully; the process remained running and no launch crash was
  observed.
- The dashboard displayed populated live voltage, current, remaining capacity,
  total capacity, and state-of-charge values.
- App-owned `KMF-BLE` logs recorded scan, GATT connection, notification
  enablement, MTU 100, `:*` startup, and parsed A/B/C protocol frames.
- The user completed the one-time companion association and the dashboard then
  reported autonomous collection as configured. Android showed the collector as
  a `connectedDevice` foreground service with an ongoing private notification
  that exposed its Stop action.
- After the app card was removed from ordinary Recents, Recents no longer
  contained the task while the collector process and its notification remained.
  The app recorded task removal followed by further GATT notifications, a
  merged reading, and a newer persisted battery sample. This verifies normal
  Recents removal, not Android Active Apps Stop or Settings force-stop.

## Autonomous physical-validation status

The following checks remain unvalidated and are not passing validation
evidence:

- companion disappearance/reappearance recovery;
- reboot restoration without opening the activity; and
- ordinary system process-death recovery; and
- notification Stop preventing reconnect.

Android Active Apps **Stop** and Settings force-stop remain intentionally
unsupported, rather than untested supported behavior. No meter address,
advertised name, GATT identifier, raw battery reading, or other device-specific
observation is retained in this committed document.
