# KMF BLE Meter Notes

## Validation Status

- Date: 2026-07-08
- Result: physical validation completed
- Evidence: on Android device `CPH2399` running Android 14, the app discovered `KMF271158`, connected, enabled CCCD notifications, parsed live `A=` and `C=` frames, wrote `:C\n`, and passed disconnect/reconnect plus relaunch-and-reconnect checks.

## Device

- Meter label: KMF271158
- Advertised name: KMF271158
- MAC address: redacted
- Android device: CPH2399
- Android version: 14

## Discovery

- `neverForLocation` scan found meter: yes
- Selected service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- Selected notify UUID: `beb5483e-36e1-4688-b7f5-ea07361b26a8`
- Selected write UUID: `beb5483e-36e1-4688-b7f5-ea07361b26a8`
- Notify and write same characteristic: yes

## Notifications

```text
redacted sample A line: :A=1327,950,0,6635,1
redacted sample C line: :C=1820,474,0,0,0,0,
```

## Polling

- Outbound poll bytes: `3A 43 0A`
- Poll interval observed: approximately 30 seconds
- First response latency: approximately 100-120 ms from logged `:C\n` write to first `C=` notification

## Failures

- Permission issues: none observed; permissions were already granted on the validation device
- Disconnect behavior: manual disconnect succeeded; reconnect succeeded; after force-stop and relaunch the app resumed in a disconnected state and recovered after rescan/reconnect with the saved profile
- Write failures: none observed for `:C\n` during validation
