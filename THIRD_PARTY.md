# Third-party components

## Receiver (`receiver/`, GPL-3.0 as a whole)

| Component | Version / commit | License | Notes |
|---|---|---|---|
| android-airplay-server (jqssun) | tag `v0.0.33`, commit `e347ee51ff3cb164d6e758496a428e3ab9b1df02` | GPL-3.0 | base of `receiver/`; our changes: `receiver/CHANGES.md` |
| UxPlay (FDH2) | submodule `df67c212a433cf6dda3676dd40c097900d24e645` + patches in `receiver/app/src/main/cpp/patches/UxPlay` | GPL-3.0 | contains further components with their own notices (see the submodule's LICENSE and file headers) |
| libplist (libimobiledevice) | submodule `f41b1ea67045e0c09339974d83e389972d84f166` | LGPL-2.1 | statically linked |
| OpenSSL | 3.4.4, built via openssl-cmake | Apache-2.0 | |
| openssl-cmake (viaduck) | submodule `4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8` | MIT | build scripts only |
| FFmpeg | submodule `38b88335f99e76ed89ff3c93f877fdefce736c13` | LGPL-2.1-or-later | built with only the ALAC/AAC decoders, no `--enable-gpl` |
| Oboe (Google) | via Maven (prefab) | Apache-2.0 | |
| AndroidX, Jetpack Compose, Media3, Hilt, Kotlin, kotlinx.coroutines, Material icons | via Maven, see `receiver/gradle/libs.versions.toml` | Apache-2.0 | |

Prebuilt native libraries: `receiver/scripts/fetch-prebuilt-native.sh` can copy the **unmodified**
native libraries (armeabi-v7a, arm64-v8a, x86_64) out of the official upstream release APK
(`https://github.com/jqssun/android-airplay-server/releases/download/v0.0.33/app-release.apk`,
SHA-256 `2203dcc76ad884265e6e01c2147c917f100e93f37b91bf728bb3eed466396b32`). Their complete
corresponding source is the upstream tag above plus the submodule commits pinned in this
repository. No binaries are committed to this repository.

## Launcher (`launcher/`, Apache-2.0)

| Component | License | Notes |
|---|---|---|
| AndroidX Media3 ExoPlayer | Apache-2.0 | via Maven |
| Inter typeface (Rasmus Andersson) | SIL Open Font License 1.1 | `launcher/licenses/Inter-OFL.txt`, also shipped inside the APK |
| Meteocons weather icons (Bas Milius) | MIT | `launcher/licenses/Meteocons-MIT.txt`, also shipped inside the APK |

## Online data services (all optional, off by default, user-supplied keys)

| Service | Used for | Terms you accept by enabling it |
|---|---|---|
| TMDB API | featured row posters | your own API key; TMDB terms of use; attribution shown in the app ("not endorsed or certified by TMDB"); watch-provider data is supplied by JustWatch via TMDB |
| API-Football (api-sports.io) | match card / goal alert | your own API key and plan limits |
| Open-Meteo | weather | free for non-commercial use, CC BY 4.0 attribution shown in the app |
| Frankfurter (ECB reference rates) | exchange rates | open API, no key |

Nothing is fetched from these services unless you put the matching section in `config.json`.

## Experimental add-on (not in release builds)

`launcher/extras/experimental-batteries-bridge` and the `experimentalBatteries` build flavor use an
undocumented web API of a third-party cloud service and the `pyicloud` Python library (MIT). They are
excluded from the default build and from published APKs. Read the warning in that folder first.
