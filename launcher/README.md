# CouchKit Home

A lightweight, glass-style home screen for Android TV / Google TV. Kotlin with plain Android Views
(no Compose, the only library is AndroidX Media3 for video). minSdk 26, targetSdk 30.
Package: `io.couchkit.home`. License: Apache-2.0.

## Features

- **Featured row**: the top row of apps drives a full-screen background. For supported streaming apps
  it shows popular titles on that service (wide image, title, type · year, overview, page dots), from
  TMDB with your own API key. Other apps get one of your own wallpapers, or a mesh gradient made from
  the app's brand colours, plus the logo cut out of its banner.
- **Glass dock and grid**: frosted dock (pre-computed blur, no live blur needed), 5:3 tiles with focus
  zoom, parallax tilt and specular rim. Long-press OK to move apps, long-press again to hide one.
- **Top widgets** (each only when configured): clock + weather, exchange rates, a followed football
  match with live score.
- **Goal pill**: a small overlay over any app when a followed team scores (needs the optional
  accessibility service).
- **Screensaver** after 5 minutes idle (also available as the system screensaver): your own videos
  and photos (slow Ken Burns pan), or an animated mesh gradient. Optional widget overlay.
- **Optional background video**: if you put videos in the screensaver folder, one plays behind the
  featured row after a few idle seconds.

### Remote

| Key | Action |
|---|---|
| D-pad | Navigate |
| OK | Open app |
| Hold OK | Move mode (tile wiggles): move with the D-pad, OK to finish |
| Hold OK in move mode | Hide the app |
| Back | From the grid back to the top row (never leaves the home screen) |

## Your files on the TV

Everything lives in the app's own external folder, `/sdcard/Android/data/io.couchkit.home/files/`
(no storage permission needed; `adb push` can write there). Nothing personal is bundled in the APK.

| Path | Purpose |
|---|---|
| `config.json` | Configuration (below). Re-read automatically when it changes. |
| `wallpapers/*.jpg\|png\|webp` | Backgrounds for apps without featured content (16:9, any resolution). |
| `screensaver/*.mp4\|mkv\|webm\|jpg\|png` | Screensaver media; the file name is shown as caption. |
| `shelf/<package>.jpg\|png\|webp` | Custom full-screen image for one app, e.g. `shelf/com.netflix.ninja.jpg`. |

## config.json

Copy `config.example.json`, edit it and push it (the repository's installer does this with
`--config`). Every section is optional; a missing section, or an empty `apiKey`, turns that feature off.

```json
{
  "weather":  {"lat": 41.01, "lon": 28.97, "label": "Istanbul"},
  "fx":       {"base": "USD", "symbols": ["EUR", "TRY"]},
  "tmdb":     {"apiKey": "...", "region": "TR", "language": "en-US"},
  "football": {"apiKey": "...", "leagues": [203, 39, 2], "season": 2026, "teams": [645, 611]},
  "screensaver": {"overlay": true}
}
```

| Key | Meaning | When absent |
|---|---|---|
| `weather.lat`, `weather.lon` | Location for Open-Meteo. `label` is shown next to the condition. | Weather hidden. |
| `fx.base`, `fx.symbols` | Shows `BASE/SYMBOL` rates (up to 4), ECB reference rates. | Rates hidden. |
| `tmdb.apiKey` | Your TMDB key (v3 API key or v4 read access token). `region`: ISO 3166-1 country for streaming availability; `language`: e.g. `en-US`. | No featured posters; wallpapers / mesh gradient. |
| `football.apiKey` | Your API-Football key (api-sports.io). `leagues`: league ids; `season`: season year as the API defines it; `teams`: team ids, in priority order. With `teams`, only matches of those teams are shown; without, any match in the leagues (earlier leagues first). | Match card and goal pill off, nothing polled. |
| `screensaver.overlay` | `true` shows clock/weather/rates/match on the screensaver. | Plain screensaver. |

Football uses the free plan's 100 requests/day carefully: today's fixtures once per league every
6 hours, live scores only while a followed match is in play (every 90 s, less often at half time or
when the budget runs low), and a hard stop at 90 requests per UTC day. Keep the league list short.

Example `wallpapers`, `screensaver` and `config.json` upload:

```bash
adb push config.json /sdcard/Android/data/io.couchkit.home/files/config.json
adb push my-photos/. /sdcard/Android/data/io.couchkit.home/files/screensaver/
```

## Build

```bash
./gradlew assembleStandardRelease
# -> app/build/outputs/apk/standard/release/app-standard-release.apk
```

You need JDK 17+ (Android Studio's bundled JBR works: set `JAVA_HOME`) and the Android SDK
(`sdk.dir` in a `local.properties` file, or `ANDROID_HOME`).

### Flavors

| Flavor | What it is |
|---|---|
| `standard` (default) | What releases ship. No account or cloud code of any kind. |
| `experimentalBatteries` | Adds an **unofficial, experimental** device-battery pill. Never released; build it yourself only after reading [`extras/experimental-batteries-bridge/README.md`](extras/experimental-batteries-bridge/README.md). |

### Signing

If a `keystore.properties` file (gitignored) exists next to `settings.gradle.kts`, release builds are
signed with it:

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, release builds are signed with your local debug key. That is fine for sideloading onto
your own TV, not for distribution.

## Making it the home screen

The repository's installer (`./install.sh` at the repository root) does all of this step by step,
verifies each change and keeps an undo journal. Manual equivalents:

Default home app (where the system allows it):

```bash
adb shell cmd package set-home-activity io.couchkit.home/.HomeActivity
```

Optional accessibility service "CouchKit Home — Home button": on Google TV builds where the default
home cannot be changed, it brings CouchKit Home to the front whenever the stock launcher's home screen
appears, and it hosts the goal pill. It reads no window content. Enable it on the TV under
Settings → System → Accessibility → CouchKit Home — Home button, or:

```bash
adb shell settings get secure enabled_accessibility_services   # note the old value first
adb shell settings put secure enabled_accessibility_services io.couchkit.home/io.couchkit.home.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

If CouchKit Home keeps crashing, the service stops redirecting after 4 automatic opens in 20 s, so
the TV falls back to the stock home screen instead of looping.

While CouchKit Home is the home screen, it asks the system to kill the *background* processes of the
previous launcher (`Launchers.PREVIOUS` in `Apps.kt`, currently the stock Google TV launcher) to free
RAM. That package is never disabled or uninstalled.

System screensaver (optional):

```bash
adb shell settings get secure screensaver_components   # note the old value first
adb shell settings put secure screensaver_components io.couchkit.home/.GlassDream
```

### Rollback

Use the installer: `./install.sh --host <TV-IP> --undo` (or `--rescue` if there is no undo journal).
Manually: set the previous home activity again with `cmd package set-home-activity`, restore the old
`enabled_accessibility_services` / `screensaver_components` values you noted, or uninstall
`io.couchkit.home`.

## Data sources and attribution

| Data | Source | Terms |
|---|---|---|
| Featured titles and images | [TMDB](https://www.themoviedb.org) API, your own key | This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming-availability data is provided to TMDB by [JustWatch](https://www.justwatch.com). Both are credited on screen while the content is shown. |
| Football fixtures, scores, team logos | [API-Football](https://www.api-football.com) (api-sports.io), your own key | Logos are loaded at runtime from the URLs the API returns; none are bundled. |
| Weather | [Open-Meteo.com](https://open-meteo.com) | CC BY 4.0, credited on screen next to the weather. |
| Exchange rates | [Frankfurter](https://www.frankfurter.app) | European Central Bank reference rates. |

Bundled assets: Inter font (SIL Open Font License 1.1) and Meteocons weather icons by Bas Milius
(MIT). License texts are in [`licenses/`](licenses) and inside the APK under `assets/licenses/`.

## Disclaimer

CouchKit is not affiliated with, endorsed by or sponsored by Apple, Google, TCL or any streaming
service. Product and app names (including package names used to sort apps or to map them to
streaming services) are used only to describe compatibility.
