# KMF BLE Meter Notes

## Validation Status

- Date: 2026-07-08
- Result: physical validation still pending
- Evidence: `adb devices` confirmed an attached Android device in this wrap-up, but no debug APK install, BLE scan, connection, or KMF meter session was exercised yet.

## Device

- Meter label: not observed
- Advertised name: not observed
- MAC address: redacted
- Android device: attached device confirmed by adb; model not recorded
- Android version: not recorded

## Discovery

- `neverForLocation` scan found meter: not tested; blocked before install/scan
- Selected service UUID: not observed
- Selected notify UUID: not observed
- Selected write UUID: not observed
- Notify and write same characteristic: not observed

## Notifications

```text
not captured; physical validation blocked before connection
```

## Polling

- Outbound poll bytes: `3A 43 0A`
- Poll interval observed: not observed
- First response latency: not observed

## Failures

- Permission issues: not evaluated on hardware in this session
- Disconnect behavior: not evaluated on hardware in this session
- Write failures: not evaluated on hardware in this session
