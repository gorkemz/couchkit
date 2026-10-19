# CouchKit Cast (receiver)

A screen-mirroring and media receiver for Android TV that works with **AirPlay-compatible senders**
(iPhone, iPad, Mac and other sender implementations on the same network).

This folder is a lightly modified copy of
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) **v0.0.33**, which is
based on [UxPlay](https://github.com/FDH2/UxPlay). All credit for the receiver itself goes to those
projects. Our changes are listed in [CHANGES.md](CHANGES.md).

License: **GPL-3.0** ([LICENSE](LICENSE)). Not affiliated with or endorsed by Apple Inc.;
"AirPlay" is used only to describe protocol compatibility. Protected content (DRM) is not supported.

## Build

Requirements: JDK 21 (or the JBR bundled with Android Studio), Android SDK with platform 37.

Fast path (uses upstream's unmodified native libraries for armeabi-v7a, arm64-v8a and x86_64, checked by SHA-256):

```
./scripts/fetch-prebuilt-native.sh
./gradlew assembleRelease -PprebuiltNative=true
```

Full source build (also needs NDK 27.0.12077973 and CMake; fetches ~1 GB of submodules):

```
git submodule update --init --depth 1
./gradlew assembleRelease
```

Signing: put your own key in `local.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). Without it the release APK is signed with your local debug key - fine for
sideloading to your own TV, not for distribution.

## Settings over adb

```
adb shell am broadcast -f 0x20 -n io.couchkit.cast/io.github.jqssun.airplay.SettingsReceiver \
  -a io.github.jqssun.airplay.SET_SETTING --es key server_name --es value "Living Room TV"
adb shell am broadcast -f 0x20 -n io.couchkit.cast/io.github.jqssun.airplay.SettingsReceiver \
  -a io.github.jqssun.airplay.LIST_SETTINGS
```

Useful keys: `server_name`, `boot_auto_start`, `run_in_background`, `low_latency`, `max_fps`,
`terminal_intro` (true/false), `welcome_text`, `goodbye_text`, `require_pin`.
