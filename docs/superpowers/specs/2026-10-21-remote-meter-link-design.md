# Remote Meter Link Design (Now tab over the network)

## Intent

The user keeps one phone at home next to the battery, connected to the KMF meter over Bluetooth, and
carries a second phone. Both phones (and the user's Mac) are on the user's NetBird network, and at home
also on the same Wi-Fi. The user wants to see the meter's live state on the carried phone, from anywhere,
in the same Cell Watch app, and later read the same data from the Mac without plugging the phone in and
exporting CSV.

The guiding idea, in the user's words: not a "source" app and a "viewer" app, but **a second way to
connect to the meter**. Today the app connects to the meter over Bluetooth. After this feature it can also
connect to the meter *through another phone* that is connected to it. The UI stays the same app, not a
different product per mode.

## Scope of this first piece

- In: the **Now tab** on the carried phone, live, through the home phone. The home phone's live feed,
  documented so the Mac (Streamlit, `curl`) can read it later without phone changes.
- Out (later pieces): Trends and battery history over the network, Streamlit integration, keeping every
  raw meter line as stored history, authentication/TLS, background collection with a notification on the
  carried phone, automatic discovery of the home phone.

## Goals

- Carried phone shows the same Now tab values (SoC, V, Ah, capacity, power, flow, Data link, pill, chart)
  as the home phone, within about a second of the home phone receiving them.
- **Same code path**: the carried phone feeds the meter's raw lines into the same `KmfLineParser`,
  `KmfReadingMerger` and `BleStateReducer` it uses for Bluetooth. Only the pipe differs. This was the
  user's condition for approving the format.
- Raw meter data is available on the network, alongside decoded values, in a standard format.
- As simple as possible and as integrated into the existing app as possible.

## Non-goals

- No history sync, no Trends over the network, no CSV over the network (yet).
- No authentication, pairing or encryption in this piece (user decision; it comes later).
- The carried phone never writes to the meter and never controls the home phone.
- No new dependency for the server or client unless the builder finds the hand-rolled approach below
  unworkable.

## Anti-goals (would be a failure even if it works)

- Two diverging apps or a drastically different UI per connection mode.
- A second, parallel decoding of meter frames on the carried phone (or in Streamlit) that can drift from
  the Bluetooth path. In particular, decoding a short A frame on its own (false remaining Ah, false low
  battery; see `docs/meter-protocol-notes.md`).
- Zero or low SoC flashing on the carried phone when it connects, reconnects or resumes.
- Any change in the home phone's Bluetooth behavior, startup sequence or history saving.
- Layout that moves with values or states (AGENTS.md hard rule), including new text such as host
  addresses or viewer counts.

## Terms

- **Home phone**: connected to the meter over Bluetooth with background collection on, and with
  **Share on network** turned on.
- **Carried phone**: connected to the meter **through another phone** (the home phone).
- These are not app variants or roles baked into builds; the same APK does either, chosen in settings.

## Wire format (agreed with the user)

Plain HTTP/1.1 on the home phone, default port **8765**, listening on all interfaces so both Wi-Fi and the
NetBird interface work. Read-only, `GET` only. Versioned under `/v1`.

### `GET /v1/live` (Server-Sent Events, `text/event-stream`)

One event per meter line, plus link-state events. Example (illustrative values):

```
event: hello
data: {"v":1,"ms":1792591392600,"meter":{"name":"KMF-BT-5A3C"},"phase":"COLLECTING"}

event: frame
data: {"ms":1792591392481,"replay":true,"line":":A=1331,5200,1,2380,152340,1800,","reading":{"voltageV":13.31,"currentA":5.2,"powerW":69.21,"charging":true,"minutesRemaining":2380,"remainingAh":152.34,"capacityAh":180.0,"socPercent":84.63,"chargeKwh":41.2,"dischargeKwh":38.9}}

event: frame
data: {"ms":1792591393012,"line":":B=1,0,0,0,","reading":{ ...same fields... }}

event: link
data: {"ms":1792591400000,"phase":"ERROR","error":"GATT 8","nextReconnectInMs":5000}

: ping
```

- `line`: the exact text of one meter line as the home phone's parser saw it, without the line
  terminator. Lines the parser ignored are relayed too (raw data is the point); the carried phone's parser
  ignores them the same way.
- `reading`: the home phone's merged `KmfReading` after applying that line, with the field names of
  `KmfReading`. This is what the Mac reads. The carried phone does not use it for display.
  `reading` is `null` until the home session has seen A, B and C (the same "complete session" rule that
  gates history samples), so the wire never carries a reading built from zeros or a lone short A frame.
  On replay lines it is the current merged state (the same object on each replay line).
- `ms`: home-phone wall-clock epoch milliseconds when the home phone received the notification holding
  that line. `hello` also carries the home phone's current `ms`; the carried phone uses it once per
  connection to compute a clock offset (`offset = own now - hello.ms`) and judges frame age as
  `own now - (frame.ms + offset)`, so a skewed clock on either phone does not make live frames look stale
  or fresh. Network latency inside the offset is accepted.
- `replay: true`: present on the lines sent right after `hello` to bring a new client up to date (see
  Replay). Absent on live lines.
- `phase`: the home phone's `CollectionPhase` name, exactly as the enum spells it. `error` and
  `nextReconnectInMs` (a relative delay, so clock skew between phones does not matter) mirror
  `CollectionState`.
- `link` is sent on every home `CollectionPhase` change.
- `: ping` is an SSE comment line sent every 15 s so both ends notice a dead connection and NetBird/NAT
  idle timers do not close it.
- Meter MAC address, service and characteristic UUIDs are not sent.

### `GET /v1/now` (JSON)

One snapshot: `{"v":1,"meter":{"name":...},"phase":...,"ms":...,"reading":{...},"lines":{"A":...,"B":...,"C":...}}`
with the last line of each type in the current home session (null when none). For `curl` and polling
from the Mac.

### Errors

Unknown path: `404`. Non-GET: `405`. More clients than the cap (builder picks, at least 4): `503`.
Sharing on but the home phone has no meter session yet: `/v1/live` still opens, sends `hello` with the
current phase and no replay lines.

## Home phone (sharing)

- New setting **Share on network**, off by default, persisted in `DeviceStore` (DataStore).
- The server runs inside `BatteryCollectionService` (already a foreground service, so it keeps running
  with the screen off), only while that service runs and sharing is on. Turning sharing on or off starts
  or stops it without restarting collection. When background collection is off there is nothing to
  share and the server is not running.
- Source of lines: the shared `BleRepository.events` stream (`NotificationReceived`). The server keeps its
  own `KmfLineParser` and `KmfReadingMerger` (the same classes, not copies) to produce `line` and
  `reading`, and resets them on the same events where `CollectionControllerImpl` resets its session
  (`Connecting`, `ServicesDiscovered`).
- To get exact lines, `KmfLineParser` exposes the lines it considered in the last `offer` (next to the
  existing `lastOfferFrames`), including its no-terminator fast path. No second line splitter.
- Source of `phase`: `CollectionController.state`.
- **Replay**: the server remembers, for the current home session, the last full A line (one that carries
  capacity), the last A line if it is newer than that, the last B line and the last C line, with their
  `ms`. A new client gets `hello`, then those lines in an order that makes the carried phone's merged
  reading equal to the home phone's (this equality is a required test), then live lines. The replay is
  cleared when the home session resets.
- Slow or stuck clients must never block the home phone's Bluetooth path: each client has its own bounded
  buffer; a client whose buffer overflows is dropped.
- The Bluetooth startup loop, polling, liveness probes and history saving are unchanged.

## Carried phone (connect through another phone)

- New connection setting: **Bluetooth** (today's behavior) or **Through another phone** with an address
  (host name or IP, optional `:port`, default 8765). NetBird DNS names (for example
  `home-phone.netbird.cloud`) and NetBird or Wi-Fi IPs both work because it is just a host name.
- The two modes are exclusive on one phone. Switching to Through another phone does the same durable stop
  of Bluetooth collection as the Device tab switch, and stops any test connection. Switching back to
  Bluetooth leaves collection off until the user turns it on (no surprise reconnects).
- A remote link component opens `/v1/live` with a plain `HttpURLConnection` (no new dependency), reads SSE
  lines, and turns them into the **existing `BleEvent`s** that `BleStateReducer` already handles:
  `Connecting` / `Connected` for the HTTP connection, `NotificationReceived` carrying the line's bytes plus
  a line terminator for each `frame`, `Disconnected` when the stream ends. `reading` from the stream is
  ignored on the phone. Writes are no-ops.
- Frame time: the reducer must use the frame's `ms` shifted by the clock offset from `hello` (and
  clamped to not be in the future) rather than the carried phone's receive time, so a replayed line from
  40 s ago shows as 40 s old (pill, Stale state, Data link) instead of fresh. How the time reaches the
  reducer is the builder's call.
- The connection mode and the address are persisted in `DeviceStore`. **Connect** saves the address,
  switches the mode to Through another phone (with the durable Bluetooth stop above) and connects;
  **Disconnect** switches the mode back to Bluetooth with collection off. While the mode is remote, the app
  reconnects every time it becomes visible.
- Remote mode is an explicit input to the Now and Device reducers, alongside
  `autonomousCollectionConfigured`: today `NowScreenReducer.resolveDisplayState` returns `CollectionOff` /
  `NoMeter` when collection is not configured, which would be wrong in remote mode. In remote mode the
  phase from the remote link drives the display state. `retryCollectionNow` (Retry / Retry now) gets a
  remote branch that reconnects to the home phone now; the Now "Start" action does not apply in remote mode
  because the remote link never reports `STOPPED`.
- The Now tab's display state comes from a `CollectionState` produced by the remote link, published
  through the existing `CollectionStatusRelay` so `NowScreenReducer` keeps its structure. The remote
  `CollectionState` carries a flag telling "home phone unreachable" apart from the home phone's own
  failures, for the Data link text below:
  - opening the HTTP connection: `CONNECTING`;
  - connected: the home phone's `phase`, `error` and next-reconnect time from `hello` / `link`;
  - home reports `STOPPED`, or the stream closes right after the home phone stops sharing: `ERROR`
    without a reconnect time (pill "Paused · Retry"); Retry reconnects to the home phone now;
  - home phone unreachable or the stream drops: `ERROR` with the carried phone's own reconnect time, so
    the pill shows "Reconnecting · n s" with Retry now. Backoff 1 s, 2 s, 5 s, then every 15 s.
  - The Data link stat for this last case reads **"phone offline"** (instead of "dropped"), so the user can
    tell "cannot reach the home phone" from "home phone lost the meter". Same text slot; the builder
    checks the worst case fits without moving layout.
- The remote link runs only while the app is visible (it follows the existing app visibility signal):
  no foreground service, no notification, no history on the carried phone in this piece. On resume it
  reconnects and the replay restores the values without zeros.
- Chart: in remote mode the Now chart is fed from an in-memory list of points built from the live
  readings received this session, in the same shape and window the chart already uses; it is not
  persisted. It starts with the existing "Chart starts with the first readings" placeholder.
- In remote mode the Now tab must not read the carried phone's own saved samples (today the newest saved
  sample can set `latestReading`); otherwise stale local values from an earlier Bluetooth session would
  show as current.
- Meter name on Now comes from `hello`. The carried phone's selected-device identity for the remote meter
  is a synthetic id derived from the address, never the meter's MAC.

## UI changes (all must also update the Cell Watch design canvas in the same task, per AGENTS.md)

- **Device tab, new "Network" card** (both phones see the same card):
  - Row **Share on network** (switch), enabled only in Bluetooth mode. While on, it shows the port, the
    phone's current IPv4 addresses (Wi-Fi and NetBird interfaces) to type on the other phone, and the
    number of connected viewers. Fixed slots sized for the worst case (two addresses, `100.100.100.100`
    style, double-digit viewer count).
  - Row **Connect through another phone**: address field and Connect / Disconnect, with the link status
    line (Connecting, Connected, Reconnecting, phone offline).
  - A short note that sharing has no password yet and anyone who can reach the phone's address can read
    the live data (read-only).
  - In remote mode the existing Bluetooth cards stay in place and show their existing off/disabled states
    with a one-line reason; no new layout per mode.
- **Now tab**: no new screen or state. Only the "phone offline" Data link text above.
- **Trends, and the Now chart tap that opens Trends**: unchanged in this piece. On the carried phone
  Trends keeps showing that phone's own saved history (empty on a phone that never collected). Trends
  over the network is the next piece.
- **First run**: unchanged. The carried phone has already finished first run; remote mode is set up
  from the Device tab only.
- Canvas boards to update: `F-Device.dc.html` (Network card, both modes), the Now Data link state board
  that shows "dropped" (add the "phone offline" variant). First-run boards are unchanged.

## Platform

- Add `android.permission.INTERNET`. No `ACCESS_NETWORK_STATE` unless the builder needs it for the
  address list.
- Cleartext HTTP must be allowed for the client (`networkSecurityConfig` with cleartext permitted;
  IP-literal hosts cannot be scoped by domain). Revisit with authentication.
- Apps with a foreground service keep network access in Doze, but incoming connections to the home phone
  with the screen off must be verified on the device (see Verification). NetBird's own app must stay
  running on both phones; that is the user's setup, not this app's.

## Logging

Per AGENTS.md, log under `KMF-BLE` (and to `files/kmf-ble.log` through `AppLog`): server start/stop and
bound port, each client connect/disconnect with remote address and reason, replay contents, buffer
overflows and drops, every relayed phase change; on the carried phone each connect attempt, HTTP status,
`hello`, phase mapping, reconnect scheduling, frame time skew when it is large, and mode switches.

## Testing and verification

JVM tests (JUnit4, `kotlinx-coroutines-test`), beside the matching packages:

- SSE encoding and decoding round trip, including multi-line safety and `: ping` handling.
- `KmfLineParser` line exposure, including the no-terminator fast path.
- **Replay equality**: for recorded line sequences (full A, short A after full A, B, C, ignored lines),
  the carried phone's merged reading after replay equals the home phone's merged reading.
- Server against real loopback sockets: `hello`, replay, live lines, `link`, ping, 404/405/503,
  slow-client drop without blocking the producer, session reset clears replay.
- Remote link: SSE lines to `BleEvent`s, phase mapping table above, backoff schedule, frame-time use.
- `NowScreenReducer`: "phone offline" text, no zero/low SoC through connect, resume and reconnect.

On device (AGENTS.md default path): build, install, launch, read app logs. Only one phone is on USB today
and no emulator is installed, and one phone cannot be both ends (modes are exclusive), so:

- **Home phone path**: on the USB phone, turn on sharing, then `curl -N http://<phone>:8765/v1/live` and
  `curl http://<phone>:8765/v1/now` from the Mac over Wi-Fi and over NetBird, including with the screen off
  for a few minutes.
- **Carried phone path**: a small Mac-side fake home phone (Python, standard library only, under
  `analytics/` or `tools/`, bound to the Mac's LAN/NetBird address only while testing) that serves
  `/v1/live` from a recorded line sequence: replay, live lines, a short-A resync, link phase changes and a
  dropped stream. The USB phone connects to it through another phone. Confirm the Now tab values, the
  pill and Data link states, and no zero or low SoC in the first seconds after connect, resume and
  reconnect (check `merged reading` lines). The recorded sequence uses illustrative values, not real
  meter observations.
- **Two-phone pass**: when the user plugs in the second phone, run the real pair once and compare both
  Now tabs.

Call out any Bluetooth regression on the home phone explicitly.

## Decided by the user

- Same app, two ways to connect; no source/viewer variants; UI as close as possible between modes.
- Scope: Now tab first; Trends, history and Streamlit later.
- Feed carries the raw A/B/C lines and decoded values; SSE format as above; same code path on the carried
  phone.
- No authentication in this piece; AGENTS.md's authenticated-transport rule is waived for it.
- First run unchanged; the carried phone is already set up, so the Device tab is the only entry point.

## My calls (the user can override)

- Hand-rolled minimal HTTP server on `java.net.ServerSocket` with coroutines and `HttpURLConnection` on
  the client, to keep dependencies at zero. The builder may switch to an established embedded server if
  the hand-rolled one becomes non-trivial.
- Port 8765, sharing off by default, server lives in the collection service.
- Manual address entry; no automatic discovery (mDNS does not cross NetBird anyway).
- Remote link only while the app is visible; no notification on the carried phone yet.
- In-memory chart on the carried phone; no history written there.
- Exclusive modes; switching to remote does a durable stop of Bluetooth collection.
- Home `STOPPED` and "home unreachable" reuse the existing Paused / Reconnecting states; only the Data
  link text "phone offline" is new.
- `reading` is null on the wire until the home session has A, B and C; `hello` carries the home clock
  for a per-connection offset (small additions to the agreed format, same idea).
- Trends unchanged on the carried phone in this piece.
- A Mac-side fake home phone script for verifying the carried-phone path with one USB phone.

## Left to the builder

- Class names and package placement (suggested: `remote/` beside `ble/`), as long as boundaries stay as
  AGENTS.md describes.
- JSON encoding approach (Android's `org.json` or a tiny writer), client cap, buffer sizes, read timeouts.
- How the frame time and the remote `CollectionState` reach the existing reducer and relay.
- Exact Device tab copy, within the fixed-slot rule and canvas sync.

