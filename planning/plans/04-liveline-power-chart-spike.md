# Liveline Power Chart Spike Plan

## Goal

Add one reversible experimental React/WebView `Power` chart beside the existing native Vico `Power` chart, using the current BLE/sample pipeline unchanged, so visual quality can be compared against measured responsiveness on device before deciding whether the approach is worth keeping.

## Current State

- `planning/plans/03-dashboard-history-vico.md` says native live charts are already in place and Task 9 historical analytics is next.
- `LiveScreen.kt` builds SoC, Power, Current, and Voltage chart models from `recentBatterySamples`; the native `Power` chart already exists.
- `KmfBleAppShell.kt` passes `BleViewModel.recentBatterySamples` into `LiveScreen`.
- `BleViewModel.kt` observes recent persisted samples through `KmfHistoryStore.observeRecentBatterySamples`; BLE startup, parser, reducer, and persistence are upstream of this.
- `KmfBatterySampleEntity.powerW` is already the stored source for live Power data.
- `app/src/main/AndroidManifest.xml` has no Internet permission, so the spike should load bundled WebView assets, not CDN assets.
- Liveline docs describe a React 18 component imported as `Liveline`, with `data` as `{ time, value }[]`, `value` as the latest number, canvas rendering, 60fps interpolation, configurable window/theme/color/formatting, and no dependencies beyond React 18 per the docs: https://benji.org/liveline

## Recommended Approach

Use a local Android `WebView` hosted from Compose via `AndroidView`, backed by bundled static React assets built from a tiny Vite app.

Brief comparison:

- Recommended: Compose `AndroidView` + local WebView assets + `evaluateJavascript` data bridge. Minimal native surface, no server, no Internet permission, easy deletion, and directly comparable inside the existing Live screen.
- React Native module/embed. Too broad for a spike; adds a second app runtime and build system surface.
- Remote hosted web chart. Not acceptable for BLE/dashboard reliability; requires network and adds latency/availability variables.
- Pure Compose reimplementation of Liveline style. Lower architectural risk, but does not answer whether actual `liveline` in WebView is viable.

## Architecture

Kotlin/native side:

- Keep BLE, parser, reducer, Room, and `recentBatterySamples` unchanged.
- Reuse `buildLiveContent(...)` and select the existing `LiveChartModel` where `title == "Power"`.
- Add an experimental card immediately after the native `Power` card on the Live page, labeled clearly as an experiment.
- Add `LivelinePowerChartWebView` composable that owns WebView setup, local asset loading, lifecycle cleanup, and JS updates.
- Convert `LiveChartModel.renderedPoints()` into a compact JSON payload: `{ points: [{ time, value }], latestValue, nativeSentAtMs }`.
- Send updates to WebView with `evaluateJavascript("window.kmfPowerChart.update(<json>)")` after page readiness.
- Add optional JS-to-Kotlin instrumentation through `addJavascriptInterface` for load and render acknowledgements only; no BLE control from JS.

WebView/React side:

- Add a tiny React entrypoint that imports `{ Liveline }` from `liveline`.
- Render only one chart: `Power`, dark theme, watts formatter, one 60-second window matching current native visible window.
- Expose `window.kmfPowerChart.update(payload)` to replace React state with the latest points and value.
- Report timing back through `window.KmfLivelineBridge.onChartAck(...)` after receiving data and after the next animation frame.

Data bridge:

- Source: `KmfBatterySampleEntity.powerW` -> existing `LiveChartModel` -> `renderedPoints()`.
- Shape: `time` should be seconds or epoch milliseconds depending on verified `liveline` behavior during implementation; default to epoch milliseconds if docs/examples confirm `formatTime(t)` expects a timestamp, otherwise use elapsed seconds and format accordingly.
- Throttle: start with one update per Compose model change; if latency or jank appears, coalesce to at most 4 updates/sec in the WebView adapter, not in BLE/repository code.

## File Scope

- `read-only` `planning/INDEX.md`
- `read-only` `planning/plans/03-dashboard-history-vico.md`
- `edit` `app/src/main/java/com/juncehome/lifepo4ble/ui/LiveScreen.kt`
- `add` `app/src/main/java/com/juncehome/lifepo4ble/ui/LivelinePowerChartWebView.kt`
- `add` `app/src/main/java/com/juncehome/lifepo4ble/ui/LivelinePowerChartModel.kt`
- `add` `app/src/main/assets/liveline-power/` for built static HTML/JS/CSS assets
- `add` `web/liveline-power-spike/package.json`
- `add` `web/liveline-power-spike/src/` React/Vite source
- `edit` `app/src/main/AndroidManifest.xml` only if WebView configuration requires explicit app settings; do not add Internet permission
- `edit` `gradle/libs.versions.toml` and `app/build.gradle.kts` only if adding `androidx.webkit` for safer asset loading or WebView compatibility helpers
- `add/edit` focused tests under `app/src/test/java/com/juncehome/lifepo4ble/ui/` for payload/model conversion only

## Implementation Steps

1. Verify `liveline` npm metadata and docs with `npm view liveline` and the docs page before installing anything; record the version chosen.
2. Add a tiny web spike project under `web/liveline-power-spike/` with React 18, Vite, and `liveline`.
3. Build a local React page that renders only `Liveline` for Power data and exposes `window.kmfPowerChart.update(payload)`.
4. Copy or wire the production web build into `app/src/main/assets/liveline-power/`.
5. Add `LivelinePowerChartModel.kt` to convert the existing native `LiveChartModel` Power points into the JSON bridge payload.
6. Add unit tests for payload ordering, latest value, timestamp/value preservation, and empty payload handling.
7. Add `LivelinePowerChartWebView.kt` using `AndroidView`, local asset loading, JavaScript enabled only for the local asset, and cleanup in `onDispose`.
8. Edit `LiveScreen.kt` to show the experimental Liveline card directly after the native `Power` chart and only when the native Power model exists.
9. Add instrumentation logging with tag `KMF-BLE` or a narrow `KMF-LIVELINE` tag for WebView load time, data send time, JS ack time, and chart frame ack time.
10. Run JVM tests, then assemble/install/launch on device, then compare native and Liveline Power charts with live BLE samples.

## Responsiveness Validation

- Sample update latency: log `nativeSentAtMs` in Kotlin, JS receive time, and next `requestAnimationFrame` ack; pass if p95 Kotlin-send-to-frame-ack is under 150 ms during normal BLE updates.
- Frame smoothness: inspect `adb shell dumpsys gfxinfo com.juncehome.lifepo4ble framestats` before/after enabling the WebView; pass if the Live screen does not introduce visible jank and missed frames stay comparable to native chart use.
- Memory/CPU: compare `adb shell dumpsys meminfo com.juncehome.lifepo4ble` and Android Studio profiler/Perfetto snapshots with native-only versus native-plus-WebView; fail if WebView adds sustained CPU drain or memory that is unreasonable for one chart.
- WebView load cost: measure time from composable creation to JS ready ack; pass if cold load is acceptable for tab navigation and warm return does not visibly stall the Live page.
- On-device chart comparison: with the meter connected and official app closed, verify native Power and Liveline Power receive the same latest values from the same sample list.
- BLE regression check: run the existing install-launch-log pass and confirm startup/readiness traffic and `Capacity Ah` / `SoC` still appear without changing BLE behavior.

## Risks

- Bundle/build complexity: React/Vite/npm assets may add maintenance cost; confirmed if the Android build needs fragile manual asset steps.
- WebView overhead: one chart may cost too much memory/CPU; confirmed by profiler, meminfo, or visible Live screen jank.
- Bridge latency: `evaluateJavascript` updates may lag under frequent samples; confirmed by p95 ack latency over threshold.
- Lifecycle bugs: WebView may leak, reload on recomposition, or lose state on navigation; confirmed by repeated Live tab switching and memory growth.
- Security surface: enabling JavaScript and JS bridge increases risk; mitigate by local-only assets, no Internet permission, no BLE commands exposed.
- Package uncertainty: npm metadata/version and actual package API still need implementation-time verification because the direct npm page was not readable in this environment.
- Visual mismatch: Liveline may look polished but not match battery analytics needs such as precise axis labeling; confirmed by user comparison against native Vico.

## Stop Conditions

Abandon the React/WebView approach if:

- It requires replacing or weakening the native Vico chart path.
- It requires changing BLE startup/parser/reducer/data persistence beyond the adapter payload.
- It needs Internet permission, remote assets, or runtime package loading.
- p95 update-to-render latency exceeds 150 ms in normal BLE use.
- WebView adds visible jank, unacceptable memory/CPU, lifecycle leaks, or unreliable tab navigation.
- The build/dependency workflow is too heavy for one visual chart.

## Open Questions

None that block implementation. The first implementation step must verify the exact npm package version and exported API before adding dependencies.

## Implementation Notes

- Verified `liveline` npm metadata at version `0.0.7`.
- Implemented the web spike, Android WebView adapter, payload conversion, tests, and Live screen wiring.
- On-device launch and log validation remain pending because `adb devices` returned no attached device during this run.
