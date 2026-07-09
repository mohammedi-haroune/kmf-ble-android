# KMF `:A=` Fields 6, 7, and 8 Follow-Up Plan

## Goal

Resume later with enough context to determine what `:A=` fields 6, 7, and 8 mean in the official Junce Home app, prove the decoding with concrete evidence, and implement only the confirmed meanings in `kmf-ble-android`.

## Why This Exists

The critical startup behavior is now understood and implemented: the meter must be driven with the same notify -> MTU 100 -> `:*` retry loop that the official app uses until both `A` and `B` state are present. That fixed the earlier issue where `Capacity Ah` and `SoC` only appeared while the official app was connected at the same time.

What remains is deeper decoding of the richer `:A=` payload. The official app clearly uses fields beyond index 5, but this repository currently exposes only the known core readings.

## Current Confirmed Context

### Confirmed startup behavior

- Official app enables notifications first.
- Official app requests `MTU 100` on Android.
- Official app sends `:*` after startup.
- Official app retries `:*` roughly every 2 seconds until both `dataObject.A` and `dataObject.B` are populated.
- `:B=` is part of real protocol state.

### Confirmed rich `:A=` payload example

Observed richer frame pattern:

```text
:A=1330,80,0,77497,103330,1450,105010450,150500,3810,
```

Known fields:

- `A[0]`: voltage, scaled by `1/100`
- `A[1]`: current magnitude, scaled by `1/1000`
- `A[2]`: charge/discharge sign flag
- `A[3]`: minutes remaining or runtime estimate
- `A[4]`: remaining Ah, scaled by `1/1000`
- `A[5]`: capacity Ah, scaled by `1/10`
- `A[6]`: packed value, meaning not fully decoded
- `A[7]`: packed value, meaning not fully decoded
- `A[8]`: unknown from the current bounded pass

## Official App Source Anchors

Primary KMF-specific source:

- `../output/resources/com.juntek.platform.apk/assets/apps/__UNI__D0FA0D1/www/app-service.js`

Known useful anchors from the current session:

- around offset `3890100`: `setBLEMTU`
- around offset `3890759`: `communication`
- around offset `3891257`: `characteristicget`
- around offset `3891639`: `monitor`
- around offset `3898704`: `writeData`
- embedded source markers around `pages/Multifunctionalmeter/kmf/index.vue:2885`, `3347`, `3416`

Generic Android BLE bridge:

- `../output/sources/io/dcloud/feature/bluetooth/BluetoothBaseAdapter.java`

Use the bridge file only to confirm the BLE primitive. Use `app-service.js` to infer protocol meaning.

## Current Field Hypotheses

These are working hypotheses, not yet final protocol facts.

### Field 6

Status: `likely`

Evidence so far:

- The official app reads `A[6]` through `getValueOne(...)`.
- The extraction patterns observed so far look like decimal slicing rather than a direct scalar measurement.
- The surrounding use suggests threshold, alarm, or protection settings rather than a live sensor reading.

Working hypothesis:

- `A[6]` is a packed configuration word that contains at least two threshold-like values, probably battery or protection related.

### Field 7

Status: `likely`

Evidence so far:

- The official app reads `A[7]` through `getValueOne(this.dataObject.A[7], 3, 2)` and `getValueOne(this.dataObject.A[7], 5, 2)`.
- The surrounding use suggests display alignment, calibration, or correction values tied to voltage/current presentation.

Working hypothesis:

- `A[7]` is a packed calibration or offset word containing multiple two-digit subfields.

### Field 8

Status: `unverified`

Evidence so far:

- A rich `:A=` frame contains an `A[8]` value.
- The current bounded pass did not complete all callsite mapping for `A[8]`.

Working hypothesis:

- `A[8]` is another packed status or configuration field, but there is not enough evidence yet to state what subsystem it belongs to.

## Investigation Method To Reuse

Follow `planning/resources/skills/kmf-official-app-field-research/SKILL.md`.

Non-negotiable sequence:

1. enumerate all `dataObject.A[6]`, `dataObject.A[7]`, and `dataObject.A[8]` callsites in `app-service.js`
2. enumerate all helper-based reads such as `getValueOne(...)` for those fields
3. capture the nearby label, UI branch, or threshold logic at each callsite
4. group the callsites by feature area
5. only then propose semantics
6. add native-app support only for the confirmed subset

## Concrete Next Tasks

- [ ] Build a callsite table for every use of `A[6]`, `A[7]`, and `A[8]` in `app-service.js`.
- [ ] For each callsite, record the exact extraction pattern, such as direct scalar read vs `getValueOne(value, start, length)`.
- [ ] Map each callsite to the user-visible feature or setting screen name if present.
- [ ] Determine whether field 6 is threshold/alarm related and list each extracted subfield.
- [ ] Determine whether field 7 is calibration/offset related and list each extracted subfield.
- [ ] Determine whether field 8 is used anywhere meaningful or only stored/pass-through.
- [ ] Decide which decoded values belong in the native app v1 UI, which belong only in diagnostics, and which should stay raw for now.
- [ ] Add parser/model tests for any newly confirmed semantics.
- [ ] Validate on device that newly surfaced values remain stable without the official app open simultaneously.

## Evidence Collection Template

Use this table format during the next pass:

| Field | Callsite anchor | Read pattern | Nearby label or branch | Most likely meaning | Confidence |
| --- | --- | --- | --- | --- | --- |
| A[6] | | | | | |
| A[7] | | | | | |
| A[8] | | | | | |

## Native App Implementation Guardrails

- Do not replace the established core scaling for fields 0 through 5.
- Do not expose guessed meanings as normal user-facing values.
- If a field is still ambiguous, expose it only as a raw packed value in diagnostics or logs.
- Keep implementation above the raw GATT layer; this is protocol decoding work, not transport work.
- Update tests before or with implementation.

## Resume Point

If resuming cold, start here:

1. open `planning/resources/skills/kmf-official-app-field-research/SKILL.md`
2. inspect `app-service.js` callsites for `A[6]`, `A[7]`, `A[8]`, and `getValueOne`
3. fill the evidence table in this plan
4. only after the table is complete, decide the smallest native-app decoding change
