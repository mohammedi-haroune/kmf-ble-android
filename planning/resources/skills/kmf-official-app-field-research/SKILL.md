# KMF Official App Field Research

## Purpose

Reuse this workflow to answer a narrow KMF protocol question by tracing how the official Junce Home Android app talks to the meter, how it decodes a target field, and how that behavior should be implemented in the native app in this repository.

Primary future use: decode `:A=` fields 6, 7, and 8, prove what they mean, and implement only the confirmed parts in `kmf-ble-android`.

## When To Use This Skill

Use this skill when all of the following are true:

- The question is about KMF BLE behavior or KMF frame decoding.
- `planning/resources/kmf.yml` is incomplete for the specific field or startup behavior being investigated.
- The official Junce Home app is available in the local reverse-engineering dump under the sibling `../output/` tree.
- You need evidence, not guesses.

Do not use this skill for generic Android BLE debugging that can already be explained by this app's own logs or code.

## Ground Rules

- Treat `planning/resources/kmf.yml` as the BLE behavior source of truth unless direct evidence proves the official app is doing something additional that matters.
- Do not hardcode observed MAC addresses or UUIDs from reverse-engineering into app logic.
- Separate generic BLE bridge code from KMF-specific protocol code before drawing conclusions.
- Label unsupported claims as `unverified`.
- Prefer callsite evidence over isolated helper functions.
- Prefer this app's own logs over broad device logs when validating runtime behavior.

## Source Map

Start from these files:

- `planning/resources/kmf.yml`
- `../output/resources/com.juntek.platform.apk/assets/apps/__UNI__D0FA0D1/www/app-service.js`
- `../output/sources/io/dcloud/feature/bluetooth/BluetoothBaseAdapter.java`

Use `app-service.js` for KMF-specific logic and UI data decoding.
Use `BluetoothBaseAdapter.java` only to confirm how the bridge performs Android BLE operations such as notification enablement, descriptor writes, characteristic writes, MTU calls, and callback dispatch.

## Exact Discovery Approach

### 1. Lock the question down first

Write the target in one sentence before searching. Example:

- "What do `:A=` fields 6, 7, and 8 represent, and how does the official app decode them?"
- "Why does the meter only emit valid `Capacity Ah` and `SoC` while the official app is also connected?"

This prevents broad, noisy grep passes.

### 2. Reconfirm the expected baseline from `kmf.yml`

Before touching the decompiled app, restate the known behavior from `planning/resources/kmf.yml`:

- line-buffered ASCII BLE notifications
- `A=` and `C=` frame parsing
- `:C\n` periodic polling
- no trusted static UUIDs or MAC addresses

Anything beyond that needs evidence from the official app or live logs.

### 3. Split generic BLE transport from KMF-specific behavior

Search the sibling reverse-engineering dump in this order:

1. `app-service.js`
2. `BluetoothBaseAdapter.java`

Reason:

- `BluetoothBaseAdapter.java` explains the transport primitives only.
- `app-service.js` contains the KMF page logic, command timing, parsed state, and field decoding.

If a behavior is only visible in `BluetoothBaseAdapter.java`, it is probably generic bridge behavior, not proof of a KMF protocol rule.

### 4. Find the KMF page entrypoints in `app-service.js`

Search for these anchors first:

- `setBLEMTU`
- `communication`
- `characteristicget`
- `monitor`
- `writeData`
- `strToObj`
- `dataObject.A`
- `dataObject.B`
- `getValueOne`

Known useful source anchors from the current investigation:

- `app-service.js` around offset `3890100`: `setBLEMTU`
- `app-service.js` around offset `3890759`: `communication`
- `app-service.js` around offset `3891257`: `characteristicget`
- `app-service.js` around offset `3891639`: `monitor`
- `app-service.js` around offset `3898704`: `writeData`
- embedded Vue source markers around `pages/Multifunctionalmeter/kmf/index.vue:2885`, `3347`, `3416`

These offsets are not stable API contracts. Use them as search anchors, not as hard assumptions.

### 5. Reconstruct the startup sequence before touching field decoding

For any protocol investigation, first prove how the official app makes the meter produce usable data.

Current confirmed sequence:

1. Enable notifications on the chosen characteristic.
2. Start receive handling (`rxd()` path).
3. On Android, request `MTU 100`.
4. After the MTU success delay, send `:*`.
5. Retry `:*` about every 2 seconds until both parsed `A` and parsed `B` data are present.
6. Only then rely on slower follow-up behavior such as `:C`.

Why this matters:

- It explained the earlier failure mode where this app only showed valid `Capacity Ah` and `SoC` while the official app was simultaneously connected.
- That symptom means the official app was continuously driving the meter into a richer reporting state. It was not just a persistent device-side one-time mode toggle.

### 6. Map the parser state model used by the official app

Trace how incoming text becomes structured data:

- find the receive path
- find `strToObj`
- confirm that parsed objects are stored by frame letter such as `A`, `B`, and `C`
- track every callsite that reads `dataObject.A[index]`, `dataObject.B[index]`, or helper-derived slices such as `getValueOne(...)`

Do not infer field meaning from one raw frame alone. Meaning comes from repeated reads plus the UI label or condition attached to that value.

### 7. For unknown fields, work callsite-first

For each target field:

1. Find every read of `dataObject.A[fieldIndex]`.
2. Find every derived read through helpers like `getValueOne(this.dataObject.A[fieldIndex], start, length)`.
3. Record the surrounding UI labels, conditions, thresholds, and formatting.
4. Group those reads by feature area: alarms, calibration, protection thresholds, display scaling, mode flags, etc.

This callsite-first method is how field 6 and field 7 moved from "extra numbers" to "packed values used by specific official app features."

### 8. Use live app behavior to validate the reverse-engineered theory

Validation order:

1. `adb shell pidof -s com.juncehome.lifepo4ble`
2. `adb logcat --pid "<pid>" -v threadtime`
3. `adb logcat -d -s KMF-BLE`
4. `adb shell run-as com.juncehome.lifepo4ble cat files/kmf-ble.log`

Look for:

- service and characteristic selection
- CCCD enablement
- MTU request result
- bootstrap writes such as `:*`
- retry loops
- first `A` and `B` arrival
- first moment `Capacity Ah` and `SoC` become valid

Only widen to broader device logs if those app-owned logs do not explain the behavior.

### 9. Convert evidence into implementation only after the meaning is bounded

Implementation rule:

- only add parser/model/UI behavior that is directly tied to an identified field meaning or startup rule
- keep unknown fields exposed as raw values until their semantics are confirmed

For field work, the minimum path is:

1. add a raw parser/model representation if needed
2. add a unit test with the observed frame
3. add a reducer or merger test for the decoded meaning
4. validate on device with logs and visible UI evidence

## Current Confirmed Findings

- The official app sends `:*` during startup, not just `:C\n`.
- The official app retries `:*` until both `A` and `B` data sets are populated.
- `:B=` is real protocol state and should not be treated as parser noise.
- Valid `Capacity Ah` and `SoC` depended on reproducing that startup sequence in this app.
- Rich `:A=` frames contain more than six fields.
- `A[6]` and `A[7]` are definitely used by the official app.
- `A[6]` appears to contain packed threshold or alarm-related values extracted with `getValueOne(...)`.
- `A[7]` appears to contain packed voltage/current alignment or calibration values extracted with `getValueOne(...)`.
- `A[8]` remains unverified from the current bounded pass and should be treated as unknown until its callsites are mapped.

## Deliverable Format For Future Passes

When using this skill, produce:

1. `Question`
2. `Evidence`
3. `Official App Callsites`
4. `Most Likely Meaning`
5. `Unverified Parts`
6. `Native App Change Needed`
7. `Minimal Validation Step`

Each conclusion must point back to a concrete `app-service.js` callsite, a helper usage pattern, or an app log line.

## Stop Conditions

Stop when all of the following are true:

- the official app callsites for the target field or behavior are enumerated
- the field meaning is either bounded or explicitly marked unverified
- the smallest native-app implementation slice is identified
- the next validation step is clear

Do not drift into broad APK re-analysis once the target field is sufficiently bounded.
