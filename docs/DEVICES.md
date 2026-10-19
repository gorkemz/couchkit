# Devices

## Verified: TCL "Smart TV Pro" (P735 series, Google TV)

| Property | Value |
|---|---|
| `ro.product.manufacturer` | TCL |
| `ro.product.device` | BeyondTV4 |
| `ro.product.name` | G03_4K_* |
| Android | 11 (SDK 30), armeabi-v7a, 2 GB RAM |
| `ro.software.version_id` | V8-R51MT05-LF1V620 |

Findings that shaped the installer:

- **Stock launcher + setup wizard.** `pm disable-user` on `com.google.android.apps.tv.launcherx`
  alone sends HOME to `com.google.android.tungsten.setupwraith`'s RecoveryActivity on the next boot.
  The TV then looks frozen. You have to disable both together, and only after another app holds the
  HOME role. With both disabled and CouchKit Home as the role holder, the TV boots straight into
  CouchKit Home.
- **Vendor boot manager.** The firmware blocks apps from starting on their own at boot, logged as
  `default_borbid`. TCL's app ops `100 APP_AUTO_START` and `101 APP_ASSOC_START` must be `allow`.
  Even then, a foreground service may not be started from a background boot receiver, so the
  receiver goes through an invisible activity. The vendor's start whitelist is on a read-only
  partition.
- **Overlay.** The receiver needs `SYSTEM_ALERT_WINDOW` to come to the front when a sender connects.
- **RAM.** Free memory at idle went from about 490 MB to about 630 MB. That came from the stock
  launcher, the setup wizard, vendor telemetry and the stock screensaver no longer running.
- **No picture/sound settings over adb.** The vendor settings provider is not exported.

## Reporting a new device

Run `./install.sh --host IP --status` (read only) and open an issue with:
- the "Device" line it prints
- which steps you ran
- what happened after a reboot

Please don't include IP addresses or account names. A model is added to `verified-devices.txt` only
after the full flow has been tested on it: install, reboot, undo, reboot.
