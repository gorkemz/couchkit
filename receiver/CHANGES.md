# Changes against upstream

Upstream: https://github.com/jqssun/android-airplay-server, tag `v0.0.33`
(commit `e347ee51ff3cb164d6e758496a428e3ab9b1df02`), GPL-3.0. Modified by the CouchKit project, October 2026.
Every modified file carries a "Modified by the CouchKit project" note at the top.

| File | Change |
|---|---|
| `app/build.gradle.kts` | application id `io.couchkit.cast`, version `0.0.33-couchkit.1`; optional `-PprebuiltNative=true` (use upstream's unmodified armeabi-v7a libraries instead of building them); release builds fall back to the local debug key when no release key is configured |
| `app/src/main/AndroidManifest.xml` | adds invisible `BootStartActivity` |
| `BootReceiver.kt`, new `BootStartActivity.kt` | on boot, start the receiver through an invisible activity: some vendor boot managers (seen on TCL Google TV) block foreground-service starts from a background boot receiver |
| `service/AirPlayService.kt` | buffers log lines while no screen is bound (for the intro); waits 0.7 s before taking over the screen so short probe connections from senders do not open the UI |
| `ui/MainScreen.kt`, new `ui/TerminalIntro.kt` | optional terminal-style intro + configurable LED greeting on connect, goodbye text and automatic close when the last sender leaves, black screen for probe-only connections, controls hidden until used |
| `Prefs.kt` | prefs `terminal_intro`, `welcome_text`, `goodbye_text`; default receiver name `CouchKit TV` |
| `download/VideoDownloader.kt` | neutral download file name |
| `res/values/strings.xml` | app name `CouchKit Cast`, neutral wording |
| `res/drawable/ic_launcher_*.xml`, `banner.xml` | original sofa icon and banner (no third-party marks) |
| `settings.gradle.kts` | project name |
| removed | upstream `.github/` release workflow, `fastlane/` store metadata, upstream README (see the upstream repository) |

Settings can be changed over adb, e.g.:

```
adb shell am broadcast -f 0x20 -n io.couchkit.cast/io.github.jqssun.airplay.SettingsReceiver \
  -a io.github.jqssun.airplay.SET_SETTING --es key welcome_text --es value "HELLO"
```

The Kotlin package stays `io.github.jqssun.airplay` to keep the diff against upstream small.
