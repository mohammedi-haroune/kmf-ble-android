# Remote Meter Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the carried phone show the home phone's live Now tab by connecting to the meter "through another phone" over Wi-Fi or NetBird, while the home phone shares raw meter lines plus decoded readings as a documented SSE feed.

**Architecture:** The home phone's collection service feeds the shared `BleRepository.events` and `CollectionController.state` into a `LiveFeedHub` (replay + fan-out) served by a minimal HTTP server on port 8765. The carried phone's `RemoteMeterLink` reads `/v1/live` and turns it back into the existing `BleEvent`s, so `KmfLineParser`, `KmfReadingMerger`, `BleStateReducer` and `NowScreenReducer` run unchanged; only the pipe and the phase source differ.

**Tech Stack:** Kotlin, Coroutines/Flow, `java.net.ServerSocket`, `HttpURLConnection`, Android built-in `org.json`, DataStore, Jetpack Compose/Material3, JUnit4 + `kotlinx-coroutines-test`; Python 3 standard library for the Mac-side fake home phone.

**Spec:** `docs/superpowers/specs/2026-10-21-remote-meter-link-design.md` (read it first; this plan argues from it).

## Global Constraints

- No new runtime dependency. Only test-only `testImplementation("org.json:json:20240303")` so `org.json` works in JVM tests (Android's copy is a stub there).
- Default port `8765`; endpoints `GET /v1/live` (`text/event-stream`) and `GET /v1/now` (`application/json`); SSE event names `hello`, `frame`, `link`; `: ping` comment every 15 s; JSON `"v":1`.
- JSON field names exactly as the spec: `v`, `ms`, `meter.name`, `phase`, `replay`, `line`, `reading` (`voltageV`, `currentA`, `powerW`, `charging`, `minutesRemaining`, `remainingAh`, `capacityAh`, `socPercent`, `chargeKwh`, `dischargeKwh`), `error`, `nextReconnectInMs`, `lines.A/B/C`.
- `reading` is `null` until the home session has seen A, B and C.
- Never send or log-to-wire the meter MAC, service UUID or characteristic UUIDs. The carried phone's remote meter id is `remote:<host>:<port>`.
- No change to the home phone's BLE startup (`:*` loop), `:C` polling, liveness probes or history saving.
- The carried phone never writes to the meter; remote link runs only while the app is visible; no notification, no history writes on the carried phone.
- Reconnect backoff on the carried phone: 1 s, 2 s, 5 s, then every 15 s.
- Data link text for "home phone unreachable" is exactly `phone offline`.
- Layout never moves with values or states (AGENTS.md hard rule): fixed slots sized for worst case (two IPv4 addresses like `100.100.100.100`, `12 viewers`, long host names ellipsized in a fixed line).
- Kotlin sources and identifiers ASCII-only; manual dependency wiring in `AppGraph`; log everything relevant through `AppLog` with tag `KMF-BLE`.
- Every visible UI change updates the Cell Watch design canvas (https://claude.ai/artifact/8qxHYzF9hc9hYWy9wbQAxm) in the same task series (Task 11).
- Commands assume the worktree root; `./gradlew` loads `JAVA_HOME` from `gradle/jdk.env`.
- Conventional Commit messages ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Home phone loses and regains the meter while a carried phone is connected: the carried phone must resync like a Bluetooth reconnect (keep last values, reset A/B flags), never zeros. Test: Task 6 `homeReconnectEmitsConnectingForSameMeter`.
2. Carried phone app goes to background for a long time and comes back: last values stay on screen until the replay arrives, no zero or low SoC. Test: Task 9 `remoteResumeKeepsLastReadingUntilReplay`.
3. Carried phone silently loses its network path (Wi-Fi to mobile, NetBird restart) without a TCP close: the read timeout must notice and reconnect. Test: Task 7 `silentServerTriggersReconnectAfterReadTimeout`.
4. User toggles Share on network off and on quickly: the server must rebind port 8765 instead of failing with "address in use". Test: Task 4 `restartOnSamePortSucceeds`.
5. A future home phone sends extra JSON fields or new event types: older carried phones ignore them without crashing. Test: Task 1 `decodeIgnoresUnknownEventsAndFields`.

---

### Task 1: Wire messages, JSON and SSE codec

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/LiveFeedMessage.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/LiveFeedJson.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/Sse.kt`
- Modify: `app/build.gradle.kts` (test dependency), `gradle/libs.versions.toml` (`orgJson = "20240303"`, library `org-json`)
- Test: `app/src/test/java/com/juncehome/lifepo4ble/remote/LiveFeedJsonTest.kt`, `app/src/test/java/com/juncehome/lifepo4ble/remote/SseTest.kt`

**Interfaces:**
- Consumes: `CollectionPhase` (`collection/CollectionController.kt`), `KmfReading`.
- Produces:
  ```kotlin
  sealed interface LiveFeedMessage {
      data class Hello(val ms: Long, val meterName: String, val phase: CollectionPhase) : LiveFeedMessage
      data class Frame(val ms: Long, val line: String, val reading: KmfReading?, val replay: Boolean = false) : LiveFeedMessage
      data class Link(val ms: Long, val phase: CollectionPhase, val error: String?, val nextReconnectInMs: Long?) : LiveFeedMessage
  }
  data class LiveFeedNow(val ms: Long, val meterName: String, val phase: CollectionPhase,
                         val reading: KmfReading?, val lines: Map<Char, String?>)
  object LiveFeedJson {
      const val VERSION = 1
      fun eventName(message: LiveFeedMessage): String          // "hello" | "frame" | "link"
      fun encode(message: LiveFeedMessage): String             // one-line JSON object
      fun encodeNow(now: LiveFeedNow): String
      fun decode(eventName: String, data: String): LiveFeedMessage?  // null for unknown event or bad JSON
  }
  data class SseEvent(val name: String, val data: String)
  object SseFormat {
      fun event(name: String, data: String): String  // "event: <name>\ndata: <data>\n\n"; '\n' in data -> extra data: lines
      fun comment(text: String): String              // ": <text>\n\n"
  }
  class SseLineReader { fun offer(line: String): SseEvent? }  // one line without terminator; event on blank line; comments ignored
  ```
  `reading` JSON omits `KmfReading.status`; decode sets `status = ""`. `replay` is written only when true. `error` / `nextReconnectInMs` written as JSON null when absent.

- [ ] **Step 1: Write the failing tests**
  - `LiveFeedJsonTest.frameEncodesSpecFieldNames`: `encode(Frame(1792591392481, ":A=1331,5200,1,2380,152340,1800,", KmfReading(voltageV = 13.31, currentA = 5.2, powerW = 69.21, charging = true, minutesRemaining = 2380, remainingAh = 152.34, capacityAh = 180.0, socPercent = 84.63, chargeKwh = 41.2, dischargeKwh = 38.9), replay = true))` parsed with `JSONObject` has `ms`, `replay == true`, `line`, and `reading.socPercent == 84.63`; no `status` key.
  - `frameWithNullReadingEncodesJsonNull`: `JSONObject(encode(...)).isNull("reading")`; no `replay` key when false.
  - `helloCarriesVersionClockNameAndPhase`: keys `v == 1`, `ms`, `meter.name == "KMF-BT-5A3C"`, `phase == "COLLECTING"`.
  - `roundTripEveryMessage`: `decode(eventName(m), encode(m)) == m` for one Hello, Frame (with and without reading), Link (`ERROR`, `"GATT 8"`, `5000`) and Link with nulls.
  - `decodeIgnoresUnknownEventsAndFields` (Review Focus 5): `decode("battery", "{}") == null`; `decode("frame", "not json") == null`; `decode("frame", """{"v":2,"ms":1,"line":":B=1,","reading":null,"extra":{"x":1}}""")` returns `Frame(1, ":B=1,", null)`; `decode("link", """{"ms":1,"phase":"WARP"}""") == null`.
  - `nowEncodesLastLinePerType`: `lines.A` string, `lines.C` JSON null.
  - `SseTest.eventAndReaderRoundTrip`: lines of `SseFormat.event("frame", "{\"a\":1}")` fed to `SseLineReader` yield `SseEvent("frame", "{\"a\":1}")` exactly once, on the blank line.
  - `readerJoinsMultipleDataLinesAndSkipsComments`: `": ping"`, `""`, `"event: x"`, `"data: a"`, `"data: b"`, `""` yields only `SseEvent("x", "a\nb")`.
  - `readerDefaultsEventNameToMessage`.
- [ ] **Step 2: Run to verify they fail**
  Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.remote.*'`
  Expected: FAIL (unresolved references).
- [ ] **Step 3: Add the test dependency and implement the three files** with `org.json.JSONObject`; decode uses `opt*` accessors so extra fields are ignored and `CollectionPhase.valueOf` failures return null.
- [ ] **Step 4: Run to verify they pass** (same command). Expected: PASS.
- [ ] **Step 5: Commit** (include the spec and this plan)
  ```bash
  git add app/build.gradle.kts gradle/libs.versions.toml app/src/main/java/com/juncehome/lifepo4ble/remote app/src/test/java/com/juncehome/lifepo4ble/remote docs/superpowers/specs/2026-10-21-remote-meter-link-design.md docs/superpowers/plans/2026-10-21-remote-meter-link.md
  git commit -m "feat(remote): add live feed wire format and SSE codec"
  ```

### Task 2: Expose parsed lines from `KmfLineParser`

**Files:**
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/protocol/KmfLineParser.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/protocol/KmfParsedLine.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/protocol/KmfLineParserTest.kt`

**Interfaces:**
- Produces: `data class KmfParsedLine(val text: String, val frame: KmfFrame?)` and `KmfLineParser.lastOfferLines: List<KmfParsedLine>` (private set), set in `finish()` next to `lastOfferFrames`. Every complete line the parser considered in the last `offer` (terminated lines, the no-terminator fast path, flushed buffered lines), in order, without terminators; empty lines and discarded oversized lines are not included. `frame` is what `parseLine` returned for it (null when ignored). `offer`'s return value and `lastOfferFrames` are unchanged.

- [ ] **Step 1: Write the failing tests**
  - `offerExposesTerminatedLines`: `offer(":A=1331,5200,1,2380,152340,1800,\r\n:B=1,0,0,0,\r\n".toByteArray())` -> `lastOfferLines.map { it.text } == listOf(":A=1331,5200,1,2380,152340,1800,", ":B=1,0,0,0,")`, first `frame is KmfFrame.A`.
  - `offerExposesSplitLineOnlyWhenComplete`: `offer(":A=1331,52")` -> empty; then `offer("00,1,2380,152340,1800,\r\n")` -> one line `":A=1331,5200,1,2380,152340,1800,"`.
  - `offerExposesUnterminatedFastPathLine`: `offer(":C=41200,38900,")` -> one line, `frame is KmfFrame.C`.
  - `offerExposesIgnoredLinesWithNullFrame`: `offer(":X=1,2\r\n")` -> `KmfParsedLine(":X=1,2", null)`.
  - `offerSkipsEmptyLines`: `offer("\r\n\r\n")` -> empty.
- [ ] **Step 2: Run to verify they fail**
  Run: `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.protocol.KmfLineParserTest'`
  Expected: FAIL.
- [ ] **Step 3: Implement** by collecting a `MutableList<KmfParsedLine>` alongside `frames` at each `parseLine` call site.
- [ ] **Step 4: Run all protocol tests** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.protocol.*'`. Expected: PASS (existing tests unchanged).
- [ ] **Step 5: Commit** `feat(protocol): expose parsed lines from KmfLineParser`

### Task 3: `LiveFeedHub` (session state, replay, fan-out)

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/LiveFeedHub.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/remote/LiveFeedHubTest.kt`

**Interfaces:**
- Consumes: Task 1 messages, Task 2 `lastOfferLines`, `BleEvent`, `CollectionState`, `KmfReadingMerger`.
- Produces:
  ```kotlin
  class LiveFeedHub(
      private val clock: () -> Long = System::currentTimeMillis,
      private val clientBufferCapacity: Int = 256,
  ) {
      val clientCount: StateFlow<Int>
      fun onBleEvent(event: BleEvent)
      fun onCollectionState(state: CollectionState)
      fun subscribe(): LiveFeedSubscription
      fun now(): LiveFeedNow
  }
  class LiveFeedSubscription(val messages: ReceiveChannel<LiveFeedMessage>, private val onClose: () -> Unit) {
      fun close()
  }
  ```
  All public methods are `@Synchronized` (producer on Main, subscribers on IO). `Connecting(device)` resets the session and sets `meterName = device.name ?: "KMF Meter"`; `ServicesDiscovered` resets the session and keeps the name. Session = fresh `KmfLineParser`, reading `KmfReading()`, A/B/C seen flags, replay slots. `NotificationReceived` -> one `Frame(ms = clock(), line.text, reading = merged-if-A,B,C-seen-else-null)` per `lastOfferLines` entry, merging that line's own frame first. Replay slots: last A with `capacityAh > 0`, last A, last B, last C (each `text` + `ms`). `subscribe()` queues `Hello(clock(), meterName, phase)` then replay frames in order [last capacity A, last A if a different line, B, C] with `replay = true` and `reading` = current reading-or-null, then live. `onCollectionState` emits `Link(clock(), phase, error, nextReconnectInMs = nextReconnectAtMs?.minus(clock())?.coerceAtLeast(0))` only when `phase` changed. A subscriber whose `trySend` fails is closed and removed (logged), never blocking the producer. Log session resets, replay contents on subscribe, drops.

- [ ] **Step 1: Write the failing tests** (helpers: `notify(text) = BleEvent.NotificationReceived(UUID(0, 0), text.toByteArray())`; reference reading = fold of `KmfReadingMerger.apply` over every frame fed in the test; viewer reading = feed each replay `line + "\r\n"` into a fresh `KmfLineParser` and fold)
  - `subscribeSendsHelloThenReplayThenLive`.
  - `replayRebuildsSameReadingAsHome` for three sequences: (a) full A charging, B, C; (b) full A charging, B, C, short A charging `":A=1331,5200,1,320,1,"`; (c) short A discharging only, then B, C. Assert viewer reading == reference reading == `now().reading` (non-null).
  - `readingIsNullUntilSessionHasABC`: frames after A and B carry `reading == null`; the frame for C carries the reference reading.
  - `ignoredLinesAreRelayed`: `":X=1,2\r\n"` produces `Frame(line = ":X=1,2")`.
  - `connectingResetsReplayAndReading`: after `Connecting`, a new subscriber gets `Hello` and no replay; `now().reading == null`, `now().lines` all null; `meterName` from the new device.
  - `phaseChangeEmitsLinkWithRelativeDelay`: clock 10_000, `CollectionState(ERROR, BACKGROUND, error = "GATT 8", nextReconnectAtMs = 15_000)` -> `Link(10_000, ERROR, "GATT 8", 5_000)`; the same phase again emits nothing.
  - `slowClientIsDroppedWithoutBlockingProducer`: capacity 4, one unread subscriber, 20 notifications complete; after draining, `messages.isClosedForReceive`; `clientCount.value == 0`; a fresh subscriber still works.
  - `twoClientsBothReceiveLiveFrames`.
  - `nowSnapshotHasLastLinePerType`.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.remote.LiveFeedHubTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `LiveFeedHub`** as specified.
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(remote): add live feed hub with replay and fan-out`

### Task 4: `LiveFeedHttpServer` and feed documentation

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/LiveFeedHttpServer.kt`
- Create: `docs/live-feed.md`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/remote/LiveFeedHttpServerTest.kt`

**Interfaces:**
- Consumes: `LiveFeedHub` (Task 3), `SseFormat`, `LiveFeedJson`.
- Produces:
  ```kotlin
  class LiveFeedHttpServer(
      private val hub: LiveFeedHub,
      private val port: Int = DEFAULT_PORT,
      private val maxClients: Int = 4,
      private val pingIntervalMs: Long = 15_000L,
      private val bindAddress: InetAddress? = null,      // null = all interfaces
      private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) {
      val boundPort: Int                   // valid after start; tests pass port = 0
      fun start(scope: CoroutineScope)     // throws BindException when the port is taken
      fun stop()                           // closes the server socket and every open stream
      companion object { const val DEFAULT_PORT = 8765 }
  }
  ```
  `ServerSocket` with `reuseAddress = true`. Per connection: read the request line and headers (max 8 KiB, 10 s timeout), then: `GET /v1/live` -> `200`, `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Connection: close`, then subscription messages as `SseFormat.event(...)` and `SseFormat.comment("ping")` every `pingIntervalMs`; `GET /v1/now` -> `200 application/json` with `Content-Length`; other paths `404`; non-GET `405` with `Allow: GET`; `/v1/live` beyond `maxClients` -> `503`. Query strings are ignored. A write failure closes that client only. Log bind, each client open/close with remote address and reason, status codes.

- [ ] **Step 1: Write the failing tests** (real loopback sockets: `port = 0`, `bindAddress = InetAddress.getLoopbackAddress()`, `runBlocking` + `withTimeout(5_000)`, plain `Socket` or `HttpURLConnection` clients)
  - `liveStreamsHelloReplayAndFrames`: hub with A, B, C fed; reading the stream through `SseLineReader` + `LiveFeedJson.decode` yields `Hello`, three replay `Frame`s, then a live `Frame` fed after connecting.
  - `nowReturnsSnapshotJson`: status 200, `Content-Type` starts with `application/json`, body `v == 1`.
  - `unknownPathIs404`, `postIs405`, `clientsBeyondCapGet503` (maxClients = 1).
  - `pingCommentSentOnInterval` (pingIntervalMs = 50): a raw line `": ping"` arrives.
  - `stopClosesStreams`: after `stop()` the client read returns EOF.
  - `restartOnSamePortSucceeds` (Review Focus 4): start, note `boundPort`, stop, start a new server on that port immediately; `/v1/now` answers.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.remote.LiveFeedHttpServerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `LiveFeedHttpServer`**; one coroutine per connection on `dispatcher`.
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Write `docs/live-feed.md`**: purpose, endpoints, the event examples from the spec, field meanings (`reading` null rule, `ms`, `replay`, `phase` = `CollectionPhase` names), `curl -N http://<phone>:8765/v1/live`, `curl http://<phone>:8765/v1/now`, a 10-line Python `urllib` reader, and the no-authentication warning. Illustrative values only.
- [ ] **Step 6: Commit** `feat(remote): serve live feed over HTTP SSE`

### Task 5: Home phone sharing (setting, service lifecycle, status, manifest)

**Files:**
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/data/DeviceStore.kt` (interface defaults + impl, key `share_on_network`, not removed by `clear()`)
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/SharingStatus.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/NetworkAddresses.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/collection/BatteryCollectionService.kt`
- Modify: `app/src/main/AndroidManifest.xml`; Create: `app/src/main/res/xml/network_security_config.xml`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/data/DeviceStoreTest.kt`, `app/src/test/java/com/juncehome/lifepo4ble/remote/NetworkAddressesTest.kt`

**Interfaces:**
- Consumes: Tasks 3-4.
- Produces:
  ```kotlin
  // DeviceSnapshotStore additions (defaults keep existing fakes compiling)
  val shareOnNetwork: Flow<Boolean> get() = flowOf(false)
  suspend fun setShareOnNetwork(enabled: Boolean) = Unit

  data class SharingStatus(val running: Boolean, val port: Int, val addresses: List<String>,
                           val viewers: Int, val error: String? = null)
  class SharingStatusRelay { val state: StateFlow<SharingStatus?>; fun publish(status: SharingStatus?) }

  object NetworkAddresses {
      fun current(): List<String>                                   // NetworkInterface scan
      fun select(interfaces: List<Pair<String, List<InetAddress>>>): List<String>
  }
  // AppGraph additions
  val sharingStatusRelay: SharingStatusRelay
  val shareOnNetwork: Flow<Boolean>
  val bleEvents: SharedFlow<BleEvent>        // = repository.events
  ```
  `select`: IPv4 only, drops loopback and link-local (`169.254.x.x`), keeps interface order with names starting `wlan` first; returns host strings. Service: in `onCreate` create `LiveFeedHub()`, collect `graph.bleEvents` with `CoroutineStart.UNDISPATCHED` into `hub::onBleEvent`, collect `controller.state` into `hub::onCollectionState` (the hub always tracks state while the service runs, so turning sharing on mid-session still has replay), collect `graph.shareOnNetwork.distinctUntilChanged()` to start/stop a `LiveFeedHttpServer(hub)`. Publish `SharingStatus` on start, stop, `hub.clientCount` changes and every 60 s while running (addresses change when NetBird or Wi-Fi reconnects); `BindException` -> `SharingStatus(running = false, port = 8765, addresses = emptyList(), viewers = 0, error = "Port 8765 is in use")`. `onDestroy` stops the server and publishes `null`. Manifest: `<uses-permission android:name="android.permission.INTERNET" />`, `android:networkSecurityConfig="@xml/network_security_config"`; config file: `<base-config cleartextTrafficPermitted="true" />` with a comment that authentication/TLS will replace it.

- [ ] **Step 1: Write the failing tests**
  - `DeviceStoreTest.shareOnNetworkDefaultsOffAndPersists`; `shareOnNetworkSurvivesClear`.
  - `NetworkAddressesTest.selectKeepsIpv4WifiFirstAndDropsLoopbackLinkLocalAndIpv6`: input `[("lo", [127.0.0.1]), ("wt0", [100.92.4.17, fd00::1]), ("wlan0", [192.168.1.23, 169.254.3.4])]` -> `["192.168.1.23", "100.92.4.17"]`.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.data.DeviceStoreTest' --tests 'com.juncehome.lifepo4ble.remote.NetworkAddressesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** store, status, addresses, AppGraph accessors, service wiring, manifest and config.
- [ ] **Step 4: Run tests and build** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS, APK built.
- [ ] **Step 5: Commit** `feat(remote): share live feed from the collection service`

### Task 6: `RemoteAddress` and `RemoteLinkMachine` (pure carried-phone logic)

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/RemoteAddress.kt`
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/RemoteLinkMachine.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/collection/CollectionController.kt` (`CollectionState` gets `val remoteUnreachable: Boolean = false`)
- Test: `app/src/test/java/com/juncehome/lifepo4ble/remote/RemoteAddressTest.kt`, `app/src/test/java/com/juncehome/lifepo4ble/remote/RemoteLinkMachineTest.kt`

**Interfaces:**
- Consumes: Task 1 messages, `BleEvent`, `ScannedDevice`, `CollectionState`.
- Produces:
  ```kotlin
  data class RemoteAddress(val host: String, val port: Int) {
      val id: String get() = "remote:$host:$port"
      val display: String get() = "$host:$port"
      val liveUrl: String get() = "http://$host:$port/v1/live"
      companion object { fun parse(text: String): RemoteAddress? }
  }
  data class TimedBleEvent(val event: BleEvent, val atMs: Long)
  class RemoteLinkMachine(private val address: RemoteAddress) {
      val state: CollectionState
      fun onConnectAttempt(nowMs: Long): List<TimedBleEvent>
      fun onMessage(message: LiveFeedMessage, nowMs: Long): List<TimedBleEvent>
      fun onStreamEnded(reason: String, nowMs: Long): RemoteRetry   // events + delay
      fun onReconnectNow()
  }
  data class RemoteRetry(val events: List<TimedBleEvent>, val delayMs: Long)
  ```
  `parse`: trims, drops a leading `http://` and trailing `/`, lowercases the host, splits one `:port` (default 8765, valid 1..65535), host matches `[a-z0-9.-]+`; anything else (empty, spaces, IPv6, bad port) returns null. Remote device = `ScannedDevice(name = meterName, address = address.id, rssi = 0, serviceUuids = emptyList())`, where `meterName` is the last `Hello` name (null before the first). Machine `state` uses `visibility = FOREGROUND` and `deviceAddress = address.id`. Rules:
  - `onConnectAttempt` -> `[Connecting(device)]`; state `CONNECTING`.
  - `Hello` -> clock offset `nowMs - hello.ms`; reset backoff; `[Connected(device)]`; state from `hello.phase`.
  - `Frame` -> `[NotificationReceived(REMOTE_CHARACTERISTIC, (line + "\r\n").toByteArray(US_ASCII))]` with `atMs = min(nowMs, frame.ms + offset)`; `reading` ignored. `REMOTE_CHARACTERISTIC` is a fixed `UUID(0L, 0L)` placeholder (never a meter UUID).
  - `Link` -> state from phase with `nextReconnectAtMs = nowMs + nextReconnectInMs`; a transition into `CONNECTING` from any other phase also emits `[Connecting(device)]` so the reducer resyncs like a Bluetooth reconnect (keeps values, clears A/B flags).
  - Home `STOPPED` (in `Hello` or `Link`) -> `ERROR`, `error = "Collection is off on the home phone"`, `nextReconnectAtMs = null`.
  - `onStreamEnded` -> `[Disconnected(reason)]`; if the last home phase was `STOPPED`: state stays as above (no timer shown) and `delayMs = 15_000`; else `delayMs` from `[1_000, 2_000, 5_000]` then `15_000`, state `ERROR` with `error = reason`, `nextReconnectAtMs = nowMs + delayMs`, `remoteUnreachable = true`, `reconnectAttempt` = 1-based streak, `linkDroppedAtMs` = first drop time of the streak.
  - `onReconnectNow` resets the backoff index.

- [ ] **Step 1: Write the failing tests**
  - `RemoteAddressTest`: `"home.netbird.cloud"` -> `("home.netbird.cloud", 8765)`; `"100.92.4.17:9000"` -> port 9000; `" http://192.168.1.23:8765/ "` ok; `"Home-Phone.NetBird.Cloud"` lowercased; `""`, `"a b"`, `"host:0"`, `"host:70000"`, `"host:abc"`, `"[::1]:8765"` -> null; `id == "remote:home.netbird.cloud:8765"`.
  - `connectAttemptEmitsConnectingWithRemoteIdentity`: device address `"remote:h:8765"`, state `CONNECTING`.
  - `helloEmitsConnectedAndMapsPhase`: `Hello(ms, "KMF-BT-5A3C", COLLECTING)` -> `Connected` with that name; state `COLLECTING`, `remoteUnreachable == false`.
  - `frameBecomesNotificationWithTerminatorAndHomeTime`: bytes equal `":B=1,0,0,0,\r\n"`.
  - `frameTimeUsesClockOffsetAndNeverFuture`: hello at home 1_000 received at 61_000 (offset 60_000); frame `ms = 2_000` received at 62_500 -> `atMs == 62_000`; frame `ms = 5_000` received at 62_500 -> `atMs == 62_500`.
  - `linkErrorUsesRelativeDelay`: `Link(_, ERROR, "GATT 8", 5_000)` at 100_000 -> `nextReconnectAtMs == 105_000`, `remoteUnreachable == false`.
  - `homeReconnectEmitsConnectingForSameMeter` (Review Focus 1): COLLECTING then `Link(CONNECTING)` -> events contain `Connecting` with the same device address as before.
  - `homeStoppedMapsToPausedWithoutTimer`; `streamEndAfterHomeStoppedKeepsPausedAndRetriesSlowly` (`delayMs == 15_000`, `nextReconnectAtMs == null`).
  - `streamEndSchedulesBackoffAndMarksUnreachable`: five consecutive ends -> delays `1000, 2000, 5000, 15000, 15000`, `reconnectAttempt` 1..5, `linkDroppedAtMs` constant.
  - `helloResetsBackoff`, `reconnectNowResetsBackoff`.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.remote.Remote*'`. Expected: FAIL.
- [ ] **Step 3: Implement** both files and the `CollectionState` field.
- [ ] **Step 4: Run all unit tests** `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(remote): map the live feed onto BLE events and collection state`

### Task 7: `RemoteMeterLink` (HTTP client loop)

**Files:**
- Create: `app/src/main/java/com/juncehome/lifepo4ble/remote/RemoteMeterLink.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt` (lazy `remoteMeterLink`, scope `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`)
- Test: `app/src/test/java/com/juncehome/lifepo4ble/remote/RemoteMeterLinkTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 3, 4 (tests), 6.
- Produces:
  ```kotlin
  interface RemoteMeterLinkContract {
      val events: SharedFlow<TimedBleEvent>
      val state: StateFlow<CollectionState?>     // null while stopped
      fun start(address: RemoteAddress)          // no-op if already running for the same address
      fun stop()
      fun reconnectNow()
  }
  class RemoteMeterLink(
      private val scope: CoroutineScope,
      private val clock: () -> Long = System::currentTimeMillis,
      private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
      private val connectTimeoutMs: Int = 10_000,
      private val readTimeoutMs: Int = 40_000,
  ) : RemoteMeterLinkContract
  ```
  `events` is a `MutableSharedFlow(extraBufferCapacity = 256)`. One loop coroutine owns the `RemoteLinkMachine` (no other caller touches it): `onConnectAttempt` -> open `HttpURLConnection(address.liveUrl)` with `Accept: text/event-stream` on `ioDispatcher` -> non-200 ends with `"HTTP <code>"` -> read lines through `SseLineReader` and `LiveFeedJson.decode` -> `onMessage` -> emit; `IOException`/EOF ends with its message (`"Home phone closed the stream"` on EOF) -> `onStreamEnded` -> wait `delayMs`, cut short by `reconnectNow()`. `stop()` cancels the loop, disconnects, sets `state` to null. Log each attempt, HTTP status, hello, mapped phase, retry delay, and clock offsets above 5 s.

- [ ] **Step 1: Write the failing tests** (real loopback: `LiveFeedHub` + `LiveFeedHttpServer(port = 0, bindAddress = loopback, pingIntervalMs = 100)`; link scope `CoroutineScope(Dispatchers.Default.limitedParallelism(1))`; `runBlocking` + `withTimeout(10_000)`)
  - `sameCodePathReadingMatchesHome`: hub fed `Connecting`, full A, B, C, short A and `CollectionState(COLLECTING)`; start link to `127.0.0.1:<boundPort>`; reduce collected events with `BleStateReducer.reduce(state, it.event, parser, it.atMs)` into a `BleUiState`; assert `latestReading` == reference fold of the same frames, `hasAFrame && hasBFrame`, and `link.state.value?.phase == COLLECTING`.
  - `serverGoneMarksUnreachableAndRetries`: stop the server -> state `ERROR`, `remoteUnreachable`, `nextReconnectAtMs != null`; start a new server on the same port -> state returns to the hub's phase.
  - `silentServerTriggersReconnectAfterReadTimeout` (Review Focus 3): a raw `ServerSocket` that sends headers and `hello` then nothing, link `readTimeoutMs = 300` -> a second connection attempt arrives within 3 s.
  - `stopSetsStateNull`.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.remote.RemoteMeterLinkTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `RemoteMeterLink`** and the `AppGraph` accessor.
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(remote): connect to a home phone live feed`

### Task 8: Remote mode in settings and the Now reducer

**Files:**
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/data/DeviceStore.kt` (key `remote_address`, kept by `clear()`)
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleUiState.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/now/NowScreenModel.kt`
- Test: `app/src/test/java/com/juncehome/lifepo4ble/data/DeviceStoreTest.kt`, `app/src/test/java/com/juncehome/lifepo4ble/ui/now/NowScreenReducerTest.kt`

**Interfaces:**
- Consumes: `CollectionState.remoteUnreachable` (Task 6), `SharingStatus` (Task 5).
- Produces:
  ```kotlin
  // DeviceSnapshotStore additions; a saved address means "through another phone"
  val remoteAddress: Flow<String?> get() = flowOf(null)
  suspend fun setRemoteAddress(address: String?) = Unit
  // BleUiState additions
  val remoteAddress: String? = null,            // RemoteAddress.display
  val remoteAddressError: String? = null,
  val shareOnNetwork: Boolean = false,
  val sharingStatus: SharingStatus? = null,
  val remoteMode: Boolean get() = remoteAddress != null
  ```
  `NowScreenReducer.resolveDisplayState`: `state.autonomousCollectionConfigured || state.remoteMode -> collection?.phase ?: CollectionPhase.CONNECTING`. `dataLink`: `Reconnecting` stat is `"phone offline"` when `state.collectionState?.remoteUnreachable == true`, else `"dropped"`.

- [ ] **Step 1: Write the failing tests**
  - `DeviceStoreTest.remoteAddressPersistsClearsAndSurvivesClear`.
  - `NowScreenReducerTest.remoteModeUsesRemotePhaseWithoutAutonomousCollection`: `autonomousCollectionConfigured = false`, `remoteAddress = "h:8765"`, `COLLECTING`, fresh frame, discharging reading -> `NowDisplayState.Discharging`.
  - `remoteModeWithoutStateIsFirstSync`.
  - `remoteUnreachableShowsPhoneOffline`: `ERROR` with `nextReconnectAtMs` and `remoteUnreachable = true` -> `Reconnecting`, `dataLink.stat == "phone offline"`, pill action `RetryNow`.
  - `homeStoppedInRemoteModeShowsPausedRetry`: `ERROR`, no timer -> pill label `"Paused · Retry"`.
  - `phoneOfflineFitsDataLinkSlot`: `"phone offline".length <= "bluetooth off".length`.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ui.now.*' --tests 'com.juncehome.lifepo4ble.data.DeviceStoreTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run all unit tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(now): drive the Now tab from a remote meter link`

### Task 9: `BleViewModel` remote wiring

**Files:**
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/BleViewModel.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/AppGraph.kt` (pass `remoteMeterLink` and `sharingStatusRelay.state` to the factory)
- Test: `app/src/test/java/com/juncehome/lifepo4ble/ui/BleViewModelTest.kt` (add `FakeRemoteMeterLink` beside the existing private fakes)

**Interfaces:**
- Consumes: Tasks 5-8.
- Produces: constructor params `remoteLink: RemoteMeterLinkContract? = null`, `sharingStatus: StateFlow<SharingStatus?>? = null`; public functions
  ```kotlin
  fun connectRemote(addressText: String)
  fun disconnectRemote()
  fun setShareOnNetwork(enabled: Boolean)
  ```
  Behavior:
  - Collect `deviceStore.remoteAddress` into `remoteAddress`; while remote and visible, `remoteLink.start(RemoteAddress.parse(it)!!)`; `onAppVisibilityChanged(BACKGROUND)` in remote mode calls `remoteLink.stop()` but keeps the values on screen; `FOREGROUND` starts again.
  - Collect `remoteLink.events` and reduce each with `BleStateReducer.reduce(state, timed.event, parser, timed.atMs)` only in remote mode. After a reduce whose `parser.lastOfferFrames` contains an A frame, prepend an in-memory `KmfBatterySampleEntity` (id 0, `timestampMs = atMs`, `deviceAddress = RemoteAddress.id`, values from `latestReading`, flags from state) to `recentBatterySamples`, dropping entries older than `NowScreenReducer.CHART_WINDOW_MS` and keeping at most `LIVE_SAMPLE_LIMIT`; newest first, like the DB flow.
  - In remote mode: `collectionState` comes from `remoteLink.state`, not the relay; `BleRepository` events are not reduced (a Bluetooth session still closing must not mix into the remote state); `syncRecentBatterySamples` DB observation is cancelled and not restarted; the `deviceStore.snapshot` collector does not overwrite `selectedDevice`; `saveSnapshot` is not called.
  - `connectRemote`: invalid text -> `remoteAddressError = "Enter a host name or IP, optionally with :port"`, nothing else changes; valid -> clear error, `stopTestConnection(reason = "remote connect")` if active, if `autonomousCollectionConfigured` run the existing `stopAutonomousCollection()` path, clear in-memory samples, `deviceStore.setRemoteAddress(address.display)`.
  - `disconnectRemote`: `remoteLink.stop()`, `deviceStore.setRemoteAddress(null)`, clear in-memory samples, restore `selectedDevice` from the saved snapshot, `collectionState` from the relay, restart `syncRecentBatterySamples` for the saved meter; collection stays off.
  - `retryCollectionNow` in remote mode -> `remoteLink.reconnectNow()`. `startCollectionFromNow`, `enableBackgroundCollection`, `startTestConnection` in remote mode are ignored with a log line.
  - Collect `deviceStore.shareOnNetwork` and `sharingStatus` into state; `setShareOnNetwork` writes the store.
  - Log every mode switch, start/stop, ignored action.

- [ ] **Step 1: Write the failing tests** (existing pattern: `CoroutineScope(UnconfinedTestDispatcher(testScheduler))`)
  - `remoteFramesUseSameReducerAndHomeTime`: remote address saved, visible, fake emits `Connecting`, `Connected`, full A `":A=1331,5200,1,2380,152340,1800,\r\n"` at `atMs = 1_000` -> `latestReading.capacityAh == 180.0`, `socPercent` = 84.63 (delta 0.01), `lastReadingTimestampMs == 1_000`.
  - `remoteModeIgnoresLocalSavedSamples`: history fake holds a sample for the saved Bluetooth meter with `socPercent = 12.0` -> in remote mode `latestReading.socPercent != 12.0` and `recentBatterySamples` holds only in-memory points.
  - `remoteChartPointsComeFromLiveAFrames`: A, B, C, A -> two points, newest first; B and C add none.
  - `connectRemoteStopsBluetoothCollectionAndSavesAddress`: configured collection -> commander `stop()` called, store address `"h:8765"`.
  - `connectRemoteRejectsInvalidAddress`.
  - `disconnectRemoteReturnsToBluetoothWithCollectionOff`.
  - `retryInRemoteModeReconnectsLink`.
  - `remoteLinkFollowsAppVisibility`.
  - `collectionStateComesFromRemoteLinkInRemoteMode`: relay `STOPPED`, link `COLLECTING` -> `uiState.collectionState?.phase == COLLECTING`.
  - `bluetoothEventsIgnoredInRemoteMode`: a `FakeBleRepository` notification with a different A frame leaves `latestReading` unchanged.
  - `remoteResumeKeepsLastReadingUntilReplay` (Review Focus 2): after values, `BACKGROUND` then `FOREGROUND`, fake emits `Connecting` (same id) -> `latestReading` unchanged and non-zero until the next frame.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ui.BleViewModelTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run all unit tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(ui): connect through another phone from the view model`

### Task 10: Device tab Network card

**Files:**
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/device/DeviceScreenModel.kt`
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/device/DeviceScreen.kt` (new `NetworkCard` after `MeterCard`, callbacks, previews for both modes and worst case)
- Modify: `app/src/main/java/com/juncehome/lifepo4ble/ui/KmfBleAppShell.kt` (wire callbacks)
- Test: `app/src/test/java/com/juncehome/lifepo4ble/ui/device/DeviceScreenReducerTest.kt`

**Interfaces:**
- Consumes: Task 8 state, Task 9 functions.
- Produces:
  ```kotlin
  data class DeviceNetworkCard(
      val shareOn: Boolean,
      val shareEnabled: Boolean,        // false in remote mode
      val shareStatus: String,
      val shareAddresses: List<String>, // always exactly 2 entries, "--" for missing
      val viewers: String,              // "0 viewers", "1 viewer", "12 viewers"
      val remoteConnected: Boolean,     // remote mode on
      val remoteAddress: String,        // saved display address or ""
      val remoteStatus: String,
      val remoteTone: DeviceTone,
      val remoteError: String?,
      val note: String,
  )
  // DeviceScreenState gains: val network: DeviceNetworkCard
  // DeviceCallbacks gains: onShareOnNetworkChange: (Boolean) -> Unit, onConnectRemote: (String) -> Unit, onDisconnectRemote: () -> Unit
  ```
  Copy (constants in `DeviceScreenReducer`): `NOTE_NO_PASSWORD = "No password yet. Anyone who can reach this phone's address can read its live data (read-only)."`, `REMOTE_OFF_REASON = "Off while connected through another phone."`, `REMOTE_TEST_BLOCKED = "Disconnect from the other phone first."`. `shareStatus`: `"Off"`; on + running -> `"Sharing · port 8765"`; on + not running + no error -> `"Waiting for background collection"`; error -> the error. `remoteStatus`: not remote -> `"Not connected"`; `CONNECTING`/null -> `"Connecting…"`; `COLLECTING`/`BOOTSTRAPPING` -> `"Connected · <meter name>"`; `remoteUnreachable` -> `"Phone offline · retry in <n> s"`; home error with timer -> `"Home phone reconnecting to meter"`; home stopped -> `"Collection is off on the home phone"`. In remote mode the Collection card shows `on = false`, `switchEnabled = false`, detail `REMOTE_OFF_REASON`, and the test card `blockedReason = REMOTE_TEST_BLOCKED`. Composable: share switch row, two fixed address lines and a fixed-width tabular viewer count, an `OutlinedTextField` (single line, `KeyboardType.Uri`, `ImeAction.Go`, initial value = saved address) with a Connect/Disconnect button of fixed width, one fixed-height ellipsized status line, the note; nothing appears, disappears or reflows with values or states.

- [ ] **Step 1: Write the failing tests**
  - `networkCardSharingShowsPortAddressesAndViewers`: status running, two addresses, 1 viewer -> `"Sharing · port 8765"`, both addresses, `"1 viewer"`.
  - `networkCardAlwaysHasTwoAddressSlots`: one address -> `listOf("192.168.1.23", "--")`; three -> first two.
  - `remoteModeDisablesShareAndBluetoothCollection`: `shareEnabled == false`, `collection.switchEnabled == false`, `collection.detail == REMOTE_OFF_REASON`, `test.blockedReason == REMOTE_TEST_BLOCKED`.
  - `remoteStatusMapsPhases`: one assertion per mapping above (meter name `"KMF-BT-5A3C"`, retry in 5 s).
  - `remoteErrorIsShown`.
- [ ] **Step 2: Run to verify they fail** `./gradlew :app:testDebugUnitTest --tests 'com.juncehome.lifepo4ble.ui.device.*'`. Expected: FAIL.
- [ ] **Step 3: Implement** model, composable, previews (remote connected, phone offline, sharing worst case with `100.100.100.100`, `12 viewers`, a 40-char host) and shell wiring.
- [ ] **Step 4: Run all unit tests and build** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(device): add network sharing and remote connection card`

### Task 11: Design canvas and design notes sync

**Files:**
- Canvas artifact https://claude.ai/artifact/8qxHYzF9hc9hYWy9wbQAxm: `F-Device.dc.html`, the Reconnecting state board on `O-Orbit.dc.html` (page "Now · Orbit cell (locked)")
- Modify: `docs/design/README.md` (Device tab: Network card; Now: "phone offline")

- [ ] **Step 1: Read the canvas** with the Artifact tool (`action: "read"`) and the two boards' files.
- [ ] **Step 2: Add the Network card to `F-Device.dc.html`** matching Task 10 exactly, with a state prop for: Bluetooth + sharing on (worst case `100.100.100.100`, `12 viewers`), Bluetooth + sharing off, remote connected, remote phone offline. Text in HTML, never `{{holes}}` inside SVG text.
- [ ] **Step 3: Add the "phone offline" Data link variant** to the Reconnecting board.
- [ ] **Step 4: Publish** to the same URL.
- [ ] **Step 5: Verify rendering locally** (memory note): copy each changed board with `./support.js` swapped for the canvas `artifact-type/dc-runtime.js`, serve with `python3 -m http.server --directory <scratch dir>`, open in the built-in browser, and read the DOM text for every state: every label and value present, nothing blank.
- [ ] **Step 6: Update `docs/design/README.md` and commit** `docs(design): add network card and phone offline state`

### Task 12: Mac-side fake home phone

**Files:**
- Create: `tools/fake_home_phone.py`
- Create: `tools/fixtures/live-session.jsonl`
- Create: `tools/tests/test_fake_home_phone.py`

**Interfaces:**
- Produces: `python3 tools/fake_home_phone.py --bind <addr> [--port 8765] [--fixture tools/fixtures/live-session.jsonl] [--interval 1.0] [--drop-after SECONDS]`; default `--bind 127.0.0.1` (LAN/NetBird only when passed explicitly). Standard library only (`http.server.ThreadingHTTPServer`, `json`). Serves `/v1/live` and `/v1/now` in the Task 1 format: `hello`, replay (last capacity A, B, C), then fixture entries in a loop at `--interval`, `: ping` every 15 s, closing the stream after `--drop-after`. Fixture lines are JSON `{"event": "frame"|"link", "data": {...}}` with illustrative values covering full A, B, C, a short A resync, a `link` to `CONNECTING` and back to `COLLECTING`, and a discharge stretch.

- [ ] **Step 1: Write the failing test** `test_live_stream_starts_with_hello_then_replay`: start the server on port 0 in a thread, read `/v1/live` with `urllib.request` until four events -> first `hello` with `v == 1`, next three `frame` with `replay == true`; `test_now_returns_json`.
- [ ] **Step 2: Run to verify it fails** `analytics/.venv/bin/python -m pytest tools/tests -q`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement** the script and fixture.
- [ ] **Step 4: Run to verify it passes** (same command). Expected: PASS.
- [ ] **Step 5: Commit** `test(tools): add fake home phone live feed for carried phone checks`

### Task 13: On-device verification

**Files:**
- Modify: `docs/live-feed.md` (sanitized "Validation" section: what was checked, no real IPs, MACs or readings)

- [ ] **Step 1: Device check** `adb devices` shows one authorized device (if none or locked, stop and ask the user).
- [ ] **Step 2: Full tests and build** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS.
- [ ] **Step 3: Install and launch** `adb install -r app/build/outputs/apk/debug/app-debug.apk` then `adb shell am start -n com.juncehome.lifepo4ble/.MainActivity`.
- [ ] **Step 4: Home phone path.** Background collection on; Device tab Share on network on (drive the UI with `adb shell uiautomator dump` + `adb shell input tap`). From the Mac: `curl -sN --max-time 20 http://<wifi-ip>:8765/v1/live` shows `hello`, replay and live frames with non-null `reading` once A, B, C are in; `curl -s http://<wifi-ip>:8765/v1/now` returns JSON; repeat with the NetBird IP. Screen off (`adb shell input keyevent 26`), wait 5 min, repeat both `curl`s. Check logs: `adb logcat --pid "$(adb shell pidof -s com.juncehome.lifepo4ble)" -v threadtime`, `adb logcat -d -s KMF-BLE`, `adb shell run-as com.juncehome.lifepo4ble cat files/kmf-ble.log` (server start, client connects, no BLE errors, `merged reading` still normal).
- [ ] **Step 5: Carried phone path.** Run `python3 tools/fake_home_phone.py --bind <mac-lan-ip> --drop-after 60`; on the phone enter `<mac-lan-ip>:8765` and Connect. Confirm in logs and UI text (`uiautomator dump`): Now values match the fixture, no zero or low SoC in the first seconds (`merged reading` lines), Reconnecting with "phone offline" after the drop, recovery on restart, resume after `adb shell input keyevent 3` + relaunch keeps values. Disconnect, turn background collection back on, confirm Bluetooth collection and `merged reading` lines resume normally (call out any regression).
- [ ] **Step 6: Two-phone pass** only if the user plugs in the second phone (`adb -s <serial>`): home on one, carried on the other, compare Now tabs.
- [ ] **Step 7: Record the sanitized validation notes and commit** `docs: record remote meter link validation`
