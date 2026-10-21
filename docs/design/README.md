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
| Stale data | no frame for > 10 s while connected | amber pill "No data · Ns"; Data link label "stalled Ns"; no fade, no banner |
| First sync | connected, no saved reading, waiting for both A and B | values "--", chart placeholder, blue "Syncing" pill |
| Resyncing | connected again, saved reading exists, waiting for A and B | last values kept in full color, blue "Syncing" pill, estimate "Refreshing estimate…" |
| Reconnecting | service retrying after a drop (1, 2, 5, 15, 30, 60 s backoff) | amber banner "Reconnecting in N s" + **Retry now** |
| Reconnecting through another phone | the carried phone cannot reach the home phone (Device › Network) | same as Reconnecting; Data link reads "phone offline" instead of "dropped" |
| Out of range | `WAITING_FOR_DEVICE` | neutral banner, no button |
| Collection error | `ERROR` | red banner with reason + **Retry** |
| Collection off | `STOPPED` | neutral banner + **Start** |

In every offline state the last values stay in full color (disconnects are frequent; never grey out the page). Cell animation stops. Chart gets a red dashed marker where data stopped. Live numbers are allowed to change every frame (it proves data is live). No dashed stream line on the flow card.

Data link while syncing or while the link is down: the tick strip gives way to one rail from the label to the state text (canvas page "Data link · offline options", option D). A soft glow sweeps it while the app gets the link going by itself (blue while syncing, fast amber when reconnecting, slow grey when out of range); it stands still when the user must act (red after an error, dark when collection or Bluetooth is off, or no meter is set up). Reduced motion parks the glow in the middle. While frames flow, every tick is full height (never small dots): missed seconds are faint red bars, and seconds before the first frame show the rail track so bars fill in from the right. Ticks and rail both run from the label right up to the state text (no fixed label slot), so the strip width follows the label length.

Layout top to bottom: header (meter name button → Device, status pill) · optional banner · SoC (big) with voltage and Ah aligned on the last text baseline · 10-segment battery bar · flow card (cell icon, state label, "2h 54m · 17:26", power W and current A on the right) · power chart last 15 min (blue above zero, amber below, end dot) · Data link card (one fixed-height line in every state: 30 ticks plus a short state label such as "58/60 · steady", "syncing 1/2", "stalled 15s", "dropped", "phone offline", "out of range", "error", "off") · "Meter totals in … · out … kWh" line.

## First run

Bluetooth off (any time, not numbered) → 1 Nearby devices permission → 2 Find meter (scan list, "Select") → 3 Android companion approval → 4 Notifications (shows notification preview) → Done "Collection is on".

## Device tab

Background collection card with switch (status, last sample age, cadences 30 s open / 5 min background) · meter card (name, address, Android pairing, wakes app when nearby, last reading, Change / Forget) · permissions list · Diagnostics "One-off test connection" (disabled with explanation while collecting; when running shows raw frame log, Clear log, Disconnect). States: Collecting, Collection off, Test running, Not paired.

Network card (between Meter and Permissions; canvas board `F-Device.dc.html`, Tweaks › network):

- Overline "NETWORK", then **Share on network** with a switch and one status line ("Sharing · port 8765", "Off", "Waiting for background collection" while the setting is on but this phone is not collecting, or the failure text "Port 8765 is in use" / "Sharing could not start"), two address slots (one line each, mono, "--" when missing, the other phone's user types them so they are never cut short) and a viewer count slot as wide as "999 viewers" ("0 viewers", "1 viewer", "12 viewers"). Addresses are primary text while sharing, tertiary when off. Worst case on the canvas: two `100.100.100.100`, `12 viewers`.
- Hairline, then **Connect through another phone**: address field "Home phone address" (56 high, mono, placeholder `192.168.1.23`, a 40 character host stays inside the field), one button that reads Connect or Disconnect (fixed to the width of "Disconnect"), and one status line with a dot: "Not connected" (neutral), "Connecting…" (neutral), "Connected · <meter name>" (live green), "Phone offline · retry in 5 s" (amber), "Home phone: ..." for the home phone's own state, or the reason the typed address was refused (red, wins over the link status).
- Note, always shown: "No password yet. Anyone who can reach this phone's address can read its live data (read-only)."
- Every slot keeps one size and position for all values and states; the card has the same height in all four canvas states (335 px). At font scale 1.1 and up the layout stacks once, whatever the values: addresses take the full width with the viewer count on its own line below them, and the field sits above a full-width button (419 px on the canvas, Tweaks › networkLayout).
- Remote connected (the other phone's meter drives Now): the switch is off and locked (sharing needs this phone's own Bluetooth collection), the field is read-only with the saved `host:port`, the button reads Disconnect. Elsewhere on the tab nothing moves, and the shipped cards show: Background collection with status "Off · no readings are saved", detail "Off while connected through another phone." and its switch disabled; the Meter card's Pair / Change / Forget actions disabled (dimmed text, same layout); One-off test connection with "Connect once" disabled and the footnote "Disconnect from the other phone first.".
- Canvas drift (pre-existing, pending a decision by the user): the canvas Device board still draws a "Watch live" Readings card (radio group) and a "Raw frames" diagnostics card, which differ from the shipped Background collection card and One-off test connection card. The remote-mode treatment drawn on those two canvas cards (Off selected with locked rows, "Disconnect from the other phone first." as the log hint) is illustrative only; the shipped cards are described above. The Network card and the disabled Change / Forget meter actions are drawn to match the code.
- States on the canvas: Bluetooth sharing on, Bluetooth sharing off, Remote connected, Remote phone offline (the header pill shows "Reconnecting · N s" there).

## Trends tab

Range tabs Live / 24 h / 7 d / 30 d · metric chips Power / SoC / Voltage / Current · current value · chart with grid, scrub line + tooltip, axis labels, Min / Average / Max · Energy card (in, out, net for the range; mirrored bars per hour or day, derived from deltas of the cumulative meter totals) · Saved history card (count, since date, Export CSV with existing exporter, last export info, Copy ADB command, Clear saved history with confirmation). Power and Current are sign-colored; SoC and Voltage single blue.

## Accessibility and motion

All animations off when Android "remove animations" is on. Screens scroll at large font sizes. Icon-only controls have content descriptions.
