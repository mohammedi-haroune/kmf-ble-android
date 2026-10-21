# Live feed (Share on network)

The home phone (the one connected to the meter over Bluetooth, with background collection and **Share on network**
on) serves what it sees over plain HTTP/1.1, read-only. A second phone (the Now tab "through another phone" mode) or
any other program on the same network can read it. Example values below are illustrative.

- Default port: `8765`, listening on all interfaces (Wi-Fi, NetBird, ...).
- `GET` only, versioned under `/v1`. Query strings are ignored.
- No authentication and no encryption: anyone who can reach the port can read the battery state. Enable it only on
  networks you trust (home Wi-Fi, a private mesh VPN) and never port-forward it to the internet.
- The meter MAC address, service UUID and characteristic UUIDs are never sent.

## Endpoints

| Request | Answer |
| --- | --- |
| `GET /v1/live` | `200`, `Content-Type: text/event-stream`: Server-Sent Events, see below |
| `GET /v1/now` | `200`, `Content-Type: application/json`: one snapshot |
| any other path | `404` |
| any method but `GET` | `405` with `Allow: GET` |
| unparseable request line / head over 8 KiB | `400` / `431` |

At most 4 live streams are open at once. When a fifth client connects, the oldest open stream is closed to make room
(there is no `503`), so a viewer that vanished without closing its connection never locks others out. `/v1/now` is
never limited.

Try it:

```
curl -N http://<phone>:8765/v1/live
curl http://<phone>:8765/v1/now
```

## `GET /v1/live`

One event per meter line plus link-state events. A new connection always receives, in this order:

1. `hello` with the home phone's clock, meter name and phase.
2. `link` with the current phase, error and retry delay (so a viewer joining during an error gets the error text).
3. Replay `frame` events (`"replay":true`): the lines needed to rebuild the home reading, at most the last full `A`
   line (the one that carries capacity), a newer short `A` line, the last `B` line and the last `C` line. Nothing
   when the home session has not seen any line yet.
4. Live `frame` events as the meter produces lines, and later `link` events.

```
event: hello
data: {"v":1,"ms":1792591392600,"meter":{"name":"KMF-BT-5A3C"},"phase":"COLLECTING"}

event: link
data: {"ms":1792591392600,"phase":"COLLECTING","error":null,"nextReconnectInMs":null}

event: frame
data: {"ms":1792591392481,"replay":true,"line":":A=1331,5200,1,2380,152340,1800,","reading":{"voltageV":13.31,"currentA":5.2,"powerW":69.21,"charging":true,"minutesRemaining":2380,"remainingAh":152.34,"capacityAh":180.0,"socPercent":84.63,"chargeKwh":41.2,"dischargeKwh":38.9}}

event: frame
data: {"ms":1792591393012,"line":":B=1,0,0,0,","reading":{ ...same fields... }}

event: link
data: {"ms":1792591400000,"phase":"ERROR","error":"GATT 8","nextReconnectInMs":5000}

: ping
```

`link` follows the home phone: it is sent right after `hello`, then whenever the phase, the error text or the next
reconnect time changes.

When the home phone stops serving on purpose, it sends one last `link` with phase `STOPPED` to every open stream
and then closes the streams. The `error` text says what was turned off: `Sharing is off on the home phone` (Share on
network switched off) or `Collection is off on the home phone` (background collection stopped). A reader should treat
that as a pause rather than a lost connection: the carried phone shows "Paused" and tries again slowly. The last
event is written after the messages already queued and within 300 ms of the stop; a stream whose peer stopped reading
is closed without it, so a stream that just ends is still possible.

```
event: link
data: {"ms":1792591500000,"phase":"STOPPED","error":"Sharing is off on the home phone","nextReconnectInMs":null}
```

A `: ping` comment is sent every 15 s so both ends notice a dead connection and idle timers (NAT, VPN) do not close
it. Readers must ignore comment lines. The server closes a stream when the client is too slow to keep up, when a write
to it stays blocked for 30 s (the client stopped reading), or when a newer client takes its place at the cap;
reconnect to get a fresh `hello`, `link` and replay.

### Fields

- `v`: wire version, `1`. Only on `hello` and `/v1/now`. Unknown events and fields must be ignored.
- `ms`: home-phone wall-clock epoch milliseconds. On `frame` it is when the home phone received the notification that
  held the line. `hello.ms` is the home phone's clock at connect: compute `offset = own now - hello.ms` once and judge
  a frame's age as `own now - (frame.ms + offset)`, so a skewed clock on either phone does not matter.
- `meter.name`: the Bluetooth name of the meter (`"KMF Meter"` when it has none).
- `phase`: the home phone's `CollectionPhase` name, exactly as the enum spells it: `STOPPED`, `BLUETOOTH_OFF`,
  `WAITING_FOR_DEVICE`, `CONNECTING`, `BOOTSTRAPPING`, `COLLECTING`, `ERROR`.
- `error`: the home phone's error text, or `null`. `nextReconnectInMs`: a relative delay until its next reconnect
  attempt, or `null` when none is pending.
- `replay`: `true` on the catch-up lines sent right after `hello` and `link`; absent on live lines.
- `line`: the exact text of one meter line as the home parser saw it, without the line terminator. Lines the parser
  ignores are relayed too.
- `reading`: the home phone's merged reading after applying that line, or `null` until the home session has seen the
  `A`, `B` and `C` frames (a reading built from zeros or from a lone short `A` frame is never sent). On replay lines
  it is the current merged state, the same on each replay line. Fields: `voltageV`, `currentA` (negative while
  discharging), `powerW` (same sign), `charging`, `minutesRemaining`, `remainingAh`, `capacityAh`, `socPercent`,
  `chargeKwh`, `dischargeKwh`.

## `GET /v1/now`

One snapshot with the last line of each type in the current home session (`null` when none yet):

```
{"v":1,"meter":{"name":"KMF-BT-5A3C"},"phase":"COLLECTING","ms":1792591393100,"reading":{ ...as above... },"lines":{"A":":A=1331,5200,1,2380,152340,1800,","B":":B=1,0,0,0,","C":":C=41200,38900,"}}
```

## Reading it from a script

```python
import json, urllib.request

with urllib.request.urlopen("http://<phone>:8765/v1/live", timeout=40) as stream:
    event = "message"
    for raw in stream:
        line = raw.decode("utf-8").rstrip("\r\n")
        if line.startswith("event: "):
            event = line[len("event: "):]
        elif line.startswith("data: "):
            print(event, json.loads(line[len("data: "):]))
```

The 40 s timeout is longer than the 15 s ping, so a silent link raises instead of hanging.
