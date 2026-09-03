# KMF BLE Meter Notes

## Autonomous-collection support boundary

- One mandatory setup is required: grant Bluetooth and notification permissions,
  select one meter, and approve Android's companion-device association sheet.
- The autonomous presence-wake guarantee requires Android API 31+ and
  `FEATURE_COMPANION_DEVICE_SETUP`.
- The selected meter must keep a stable address, or use an address Android can
  resolve after bonding.
- An ordinary Recents swipe is a supported design path: the collection service
  is declared to remain running and reconnect when Android reports the
  companion meter present.
- Android Active Apps **Stop** and Settings force-stop are unsupported. Android
  places the package in a stopped state, so the app cannot restart itself until
  the user starts it again.

## 2026-09-03 device evidence

- An authorized, unlocked Android device accepted the debug APK and launched
  the app successfully; the process remained running and no launch crash was
  observed.
- The dashboard displayed populated live voltage, current, remaining capacity,
  total capacity, and state-of-charge values.
- App-owned `KMF-BLE` logs recorded scan, GATT connection, notification
  enablement, MTU 100, `:*` startup, and parsed A/B/C protocol frames.
- The user completed the one-time companion association and the dashboard then
  reported autonomous collection as configured. Android showed the collector as
  a `connectedDevice` foreground service with an ongoing private notification
  that exposed its Stop action.
- After the app card was removed from ordinary Recents, Recents no longer
  contained the task while the collector process and its notification remained.
  The app recorded task removal followed by further GATT notifications, a
  merged reading, and a newer persisted battery sample. This verifies normal
  Recents removal, not Android Active Apps Stop or Settings force-stop.

## Autonomous physical-validation status

The following checks remain unvalidated and are not passing validation
evidence:

- companion disappearance/reappearance recovery;
- reboot restoration without opening the activity; and
- notification Stop preventing reconnect.

Android Active Apps **Stop** and Settings force-stop remain intentionally
unsupported, rather than untested supported behavior. No meter address,
advertised name, GATT identifier, raw battery reading, or other device-specific
observation is retained in this committed document.
