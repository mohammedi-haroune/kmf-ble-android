# App redesign spec (2026-10-18)

Source of truth: the Claude Design canvas "KMF BLE App" (owner's claude.ai account). The `*.dc.html` files here are exported copies of the final boards. Each is HTML with inline styles; the `data-props` enum at the bottom lists the states, and `renderVals()` holds per-state copy, colors and sample values. Values like 82 %, 13.28 V and timestamps are placeholders; real values come from app state.

| File | Screen |
|---|---|
| `now-tab.dc.html` | Now tab (replaces Dashboard + Live) |
| `first-run.dc.html` | First-run setup flow |
| `device-tab.dc.html` | Device tab (replaces Diagnostic) |
| `trends-tab.dc.html` | Trends tab (replaces Live chart, History, Energy) |

## Navigation

3 bottom tabs: **Now**, **Trends**, **Device** (line icons, no letters). First run is shown instead of the tabs until setup is done.

## Tokens

- Background `#0D0F12`, surface `#16191E`, surface-2 / hairline `#1E2228`, border `#2C323A`
- Text `#F2F4F7`, secondary `#9AA3AE`, tertiary `#7D8692`, soft text `#C9D0D8`
- Charging / positive `#4FB8FF`, discharging / negative `#FFB23F`, live / ok `#3DDC97`, error `#FF6B6B` (text on tint `#FF8A8A`), neutral off `#9AA3AE`
- Tints: accent at 8 % for card fill, 14 % for icon tiles and pills, 22–35 % for borders
- Type: IBM Plex Sans (UI) and IBM Plex Mono (all numbers, tabular). Bundle the fonts, no network fetch.
- Radius: cards 16, inner tiles 12, pills 999. Screen padding 20, section gap 16. Touch targets ≥ 44 dp.
- Units always shown smaller and in secondary color next to the value. Negative numbers use the true minus sign "−".

## Connection ownership

The autonomous `BatteryCollectionService` / `CollectionController` is the only normal owner of the Bluetooth link. The UI observes it. A one-off manual connection exists only in Device › Diagnostics, enabled only while collection is off (matches `manualTransportControlsEnabled`).

## Now tab states (approved rules)

| State | Trigger | Visual |
|---|---|---|
| Charging / Discharging | collecting, sign of current | accent blue / amber, battery-cell wave animation in flow card |
| Low battery | SoC < 20 % | red SoC number, red bar, alert row under the bar |
| Live pill | last frame ≤ 5 s old | green pill "Live · Ns ago" with pulse; 5–10 s green without pulse |
| Stale data | no frame for > 10 s while connected | amber pill "No data · Ns"; Data link card expands; no fade, no banner |
| First sync | connected, no saved reading, waiting for both A and B | values "--", chart placeholder, blue "Syncing" pill |
| Resyncing | connected again, saved reading exists, waiting for A and B | last values kept in full color, blue "Syncing" pill, estimate "Refreshing estimate…" |
| Reconnecting | service retrying after a drop (1, 2, 5, 15, 30, 60 s backoff) | amber banner "Reconnecting in N s" + **Retry now** |
| Out of range | `WAITING_FOR_DEVICE` | neutral banner, no button |
| Collection error | `ERROR` | red banner with reason + **Retry** |
| Collection off | `STOPPED` | neutral banner + **Start** |

In every offline state the last values stay in full color (disconnects are frequent; never grey out the page). Cell animation stops. Chart gets a red dashed marker where data stopped. Live numbers are allowed to change every frame (it proves data is live). No dashed stream line on the flow card.

Layout top to bottom: header (meter name button → Device, status pill) · optional banner · SoC (big) with voltage and Ah aligned on the last text baseline · 10-segment battery bar · flow card (cell icon, state label, "2h 54m · 17:26", power W and current A on the right) · power chart last 15 min (blue above zero, amber below, end dot) · Data link card (compact one line with 30 ticks when healthy, expanded 60 ticks + caption when not) · "Meter totals in … · out … kWh" line.

## First run

Bluetooth off (any time, not numbered) → 1 Nearby devices permission → 2 Find meter (scan list, "Select") → 3 Android companion approval → 4 Notifications (shows notification preview) → Done "Collection is on".

## Device tab

Background collection card with switch (status, last sample age, cadences 30 s open / 5 min background) · meter card (name, address, Android pairing, wakes app when nearby, last reading, Change / Forget) · permissions list · Diagnostics "One-off test connection" (disabled with explanation while collecting; when running shows raw frame log, Clear log, Disconnect). States: Collecting, Collection off, Test running, Not paired.

## Trends tab

Range tabs Live / 24 h / 7 d / 30 d · metric chips Power / SoC / Voltage / Current · current value · chart with grid, scrub line + tooltip, axis labels, Min / Average / Max · Energy card (in, out, net for the range; mirrored bars per hour or day, derived from deltas of the cumulative meter totals) · Saved history card (count, since date, Export CSV with existing exporter, last export info, Copy ADB command, Clear saved history with confirmation). Power and Current are sign-colored; SoC and Voltage single blue.

## Accessibility and motion

All animations off when Android "remove animations" is on. Screens scroll at large font sizes. Icon-only controls have content descriptions.
